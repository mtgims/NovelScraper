"""Job manager: in-process async worker that runs scrapes.

Each submitted scrape becomes an asyncio task. A semaphore caps how many run at
once; live progress is fanned out via the :class:`EventBus` and snapshotted into
the database. Cancellation cancels the task; the scraper's disk cache means a
re-submitted job effectively resumes.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional

from sqlalchemy import func
from sqlmodel import Session, select

from ..models import ArchivedProgress, Book, Chapter, Job, JobStatus, ReadingProgress, Volume
from ..scraper import ScraperConfig, SiteProfile, resolve_book_url, scrape_book
from ..scraper.models import Chapter as ScrapedChapter
from ..scraper.models import VolumeResult as ScrapedVolume
from ..scraper.parser import count_words
from ..schemas import JobCreate, JobRead
from .events import EventBus

logger = logging.getLogger(__name__)

# Persist progress counters to the DB at most every N fetched chapters, to avoid
# a write per chapter. The live counts are always exact via the event bus.
PERSIST_EVERY = 25

TERMINAL = {JobStatus.completed.value, JobStatus.failed.value, JobStatus.cancelled.value}
ACTIVE = (JobStatus.queued.value, JobStatus.running.value)


class DuplicateJobError(Exception):
    """Raised when an active job already exists for the same site+book."""


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class JobManager:
    def __init__(self, engine, profiles: Dict[str, SiteProfile], settings) -> None:
        self.engine = engine
        self.profiles = profiles
        self.settings = settings
        self.bus = EventBus()
        self._sem = asyncio.Semaphore(settings.max_concurrent_jobs)
        self._tasks: Dict[str, asyncio.Task] = {}
        self._live: Dict[str, Dict[str, Any]] = {}

    # --- public API ------------------------------------------------------

    def submit(self, data: JobCreate, user_id: int, incremental: bool = False) -> Job:
        # Resolve the pasted URL to a site profile + book id (raises
        # UnsupportedSourceError / ProfileError, handled by the endpoint).
        profile, slug = resolve_book_url(data.url, self.profiles)
        with Session(self.engine) as s:
            # Reject a second active job for the same book *by the same user*:
            # concurrent runs would race on clearing/writing that user's volumes,
            # chapters and EPUB files. Different users own separate copies, so
            # they may scrape the same novel at the same time.
            active = s.exec(
                select(Job).where(
                    Job.site == profile.name,
                    Job.book_slug == slug,
                    Job.user_id == user_id,
                    Job.status.in_(ACTIVE),
                )
            ).first()
            if active is not None:
                raise DuplicateJobError(active.id)
            job = Job(
                id=uuid.uuid4().hex,
                site=profile.name,
                book_slug=slug,
                source_url=data.url,
                user_id=user_id,
                chapters_per_volume=data.chapters_per_volume or 100,
                delay=data.delay,
                concurrency=data.concurrency,
                incremental=incremental,
            )
            s.add(job)
            s.commit()
            s.refresh(job)
        self._tasks[job.id] = asyncio.create_task(self._run(job.id))
        return job

    def cancel(self, job_id: str) -> bool:
        task = self._tasks.get(job_id)
        if task is None or task.done():
            return False
        task.cancel()
        return True

    def snapshot(self, job_id: str) -> Optional[dict]:
        """Current job state as a JSON-serializable dict, merged with live counts."""
        job = self._get(job_id)
        if job is None:
            return None
        data = JobRead.model_validate(job).model_dump(mode="json")
        live = self._live.get(job_id)
        if live:
            data["total_chapters"] = max(data["total_chapters"], live["total"])
            data["fetched_chapters"] = live["fetched"]
            data["skipped_chapters"] = live["skipped"]
            data["phase"] = live["phase"]
        return data

    async def shutdown(self) -> None:
        tasks = [t for t in self._tasks.values() if not t.done()]
        for t in tasks:
            t.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    # --- worker ----------------------------------------------------------

    def _build_config(self, job: Job) -> ScraperConfig:
        kwargs: Dict[str, Any] = dict(
            output_dir=str(self.settings.output_dir),
            cache_dir=str(self.settings.cache_dir),
            cover_dir=str(self.settings.cover_dir),
            allow_private_hosts=self.settings.allow_private_hosts,
            chapters_per_volume=job.chapters_per_volume,
            # Route fetches through this user's phone when it has a relay socket
            # connected; falls back to a server-side fetch otherwise.
            relay_user_id=job.user_id,
        )
        if job.delay is not None:
            kwargs["delay"] = job.delay
        if job.concurrency is not None:
            kwargs["max_concurrency"] = job.concurrency
        return ScraperConfig(**kwargs)

    async def _run(self, job_id: str) -> None:
        try:
            async with self._sem:
                job = self._get(job_id)
                if job is None:
                    return
                self._update(job_id, status=JobStatus.running.value,
                             phase="enumerating", started_at=_utcnow())
                profile = self.profiles[job.site]
                config = self._build_config(job)

                live = {"total": 0, "fetched": 0, "skipped": 0,
                        "phase": "enumerating", "volume": 0}
                self._live[job_id] = live
                # For an incremental update, resume from what's already saved:
                # start_position = highest saved chapter. New chapters continue
                # filling the last (partial) volume, so we match the book's
                # existing volume size rather than the job's default.
                start_position = 0
                existing_book_id = None
                if job.incremental:
                    start_position, cpv, existing_book_id = self._update_offsets(job)
                    config.chapters_per_volume = cpv

                # Book is created lazily on the first completed volume (fresh
                # scrape), so a job cancelled/failed before any output neither
                # creates an empty book nor destroys a previously-scraped one.
                # For updates the book already exists, so book_id is set upfront.
                state = {"book_id": existing_book_id, "position": start_position}

                def on_volume(book, volume: ScrapedVolume,
                              chapters: list[ScrapedChapter]) -> None:
                    if state["book_id"] is None:
                        state["book_id"] = self._prepare_book(job, book)
                        self._update(job_id, book_id=state["book_id"])
                    state["position"] = self._persist_volume(
                        state["book_id"], volume, chapters, state["position"])

                def on_progress(phase: str, payload: dict) -> None:
                    if phase == "enumerating":
                        live["total"] = payload.get("found", live["total"])
                        live["phase"] = "enumerating"
                    elif phase == "fetched":
                        live["fetched"] += 1
                        if not payload.get("ok"):
                            live["skipped"] += 1
                        live["phase"] = "fetching"
                        if live["fetched"] % PERSIST_EVERY == 0:
                            self._update(job_id, total_chapters=live["total"],
                                         fetched_chapters=live["fetched"],
                                         skipped_chapters=live["skipped"],
                                         phase="fetching")
                    elif phase == "building":
                        live["phase"] = "building"
                        live["volume"] = payload.get("volume", live["volume"])
                    self.bus.publish(job_id, {"event": "progress", **live})

                result = await scrape_book(job.book_slug, profile, config,
                                           on_progress, on_volume,
                                           source_url=job.source_url,
                                           start_position=start_position)
                # Record the check time even when an update found no new chapters.
                if state["book_id"] is not None:
                    self._touch_book(state["book_id"])
                self._update(
                    job_id, status=JobStatus.completed.value, phase="done",
                    total_chapters=result.total_chapters,
                    fetched_chapters=live["fetched"],
                    skipped_chapters=result.skipped_chapters,
                    book_id=state["book_id"], finished_at=_utcnow(),
                )
                self.bus.publish(job_id, {"event": "completed",
                                          "book_id": state["book_id"],
                                          "volumes": len(result.volumes)})

        except asyncio.CancelledError:
            self._update(job_id, status=JobStatus.cancelled.value, phase="done",
                         finished_at=_utcnow())
            self.bus.publish(job_id, {"event": "cancelled"})
            # Handled at task top level; do not re-raise.
        except Exception as e:  # noqa: BLE001 - record any scrape failure
            logger.exception("job %s failed", job_id)
            self._update(job_id, status=JobStatus.failed.value, phase="done",
                         error=str(e), finished_at=_utcnow())
            self.bus.publish(job_id, {"event": "failed", "error": str(e)})
        finally:
            self._live.pop(job_id, None)
            self._tasks.pop(job_id, None)

    # --- persistence helpers --------------------------------------------

    def _get(self, job_id: str) -> Optional[Job]:
        with Session(self.engine) as s:
            return s.get(Job, job_id)

    def _update(self, job_id: str, **fields) -> None:
        with Session(self.engine) as s:
            job = s.get(Job, job_id)
            if job is None:
                return
            for key, value in fields.items():
                setattr(job, key, value)
            s.add(job)
            s.commit()

    def _update_offsets(self, job: Job) -> tuple[int, int, Optional[int]]:
        """For an incremental update: (highest saved chapter position, effective
        chapters-per-volume, existing book id). The cpv is recovered from the
        book's existing volumes — every full volume equals the original size, so
        the largest one does — so new chapters keep filling the last volume at the
        same size instead of each update starting a new one. Zeros/None if the
        book doesn't exist yet (falls back to a full scrape)."""
        with Session(self.engine) as s:
            book = s.exec(
                select(Book).where(
                    Book.slug == job.book_slug,
                    Book.site == job.site,
                    Book.user_id == job.user_id,
                )
            ).first()
            if book is None:
                return 0, job.chapters_per_volume, None
            max_pos = s.exec(
                select(func.max(Chapter.position)).where(Chapter.book_id == book.id)
            ).one()
            max_vol_size = s.exec(
                select(func.max(Volume.chapter_count)).where(Volume.book_id == book.id)
            ).one()
            cpv = max_vol_size or job.chapters_per_volume
            return (max_pos or 0), cpv, book.id

    def _touch_book(self, book_id: int) -> None:
        with Session(self.engine) as s:
            book = s.get(Book, book_id)
            if book is not None:
                book.updated_at = _utcnow()
                s.add(book)
                s.commit()

    def _prepare_book(self, job: Job, scraped) -> int:
        """Get-or-create the book row (with scraped metadata) and clear any prior
        volumes/chapters so a re-scrape replaces them. Returns the book id."""
        with Session(self.engine) as s:
            book = s.exec(
                select(Book).where(
                    Book.slug == job.book_slug,
                    Book.site == job.site,
                    Book.user_id == job.user_id,
                )
            ).first()
            is_new = book is None
            if is_new:
                book = Book(slug=job.book_slug, site=job.site, user_id=job.user_id)
            book.title = scraped.display_title()
            book.author = scraped.author
            book.language = scraped.language
            book.cover_path = scraped.cover_path
            if is_new:
                book.created_at = _utcnow()  # preserve add-date on re-scrape/update
            if job.source_url:
                book.source_url = job.source_url  # remember it so we can update later
            book.updated_at = _utcnow()
            s.add(book)
            s.commit()
            s.refresh(book)

            for old_v in s.exec(select(Volume).where(Volume.book_id == book.id)).all():
                s.delete(old_v)
            for old_c in s.exec(select(Chapter).where(Chapter.book_id == book.id)).all():
                s.delete(old_c)
            s.commit()

            # Restore reading progress archived when this novel was last deleted
            # (only if the book has none yet, so a normal re-scrape isn't clobbered).
            has_progress = s.exec(
                select(ReadingProgress).where(ReadingProgress.book_id == book.id)
            ).first()
            if has_progress is None:
                arch = s.exec(
                    select(ArchivedProgress).where(
                        ArchivedProgress.user_id == job.user_id,
                        ArchivedProgress.site == job.site,
                        ArchivedProgress.slug == job.book_slug,
                    )
                ).first()
                if arch is not None:
                    s.add(ReadingProgress(
                        book_id=book.id,
                        last_position=arch.last_position,
                        scroll=arch.scroll,
                        read_positions=list(arch.read_positions),
                    ))
                    s.commit()
                    logger.info("restored archived progress for %s/%s",
                                job.site, job.book_slug)
            return book.id

    def _persist_volume(self, book_id: int, volume: ScrapedVolume,
                        chapters: list, position: int) -> int:
        """Persist one volume and its content-bearing chapters (incrementally,
        so a cancelled job still leaves the completed volumes readable). Returns
        the updated running position."""
        with Session(self.engine) as s:
            # Append to the volume if it already exists (an incremental update
            # filling the last partial volume), else create it. EPUBs are built on
            # demand, so there's no stored file/size.
            vol = s.exec(
                select(Volume).where(
                    Volume.book_id == book_id, Volume.number == volume.number
                )
            ).first()
            if vol is None:
                vol = Volume(book_id=book_id, number=volume.number,
                             title=volume.title, path="", chapter_count=0,
                             size_bytes=0)
            vol.chapter_count += volume.chapter_count
            s.add(vol)
            for ch in chapters:
                if not ch.content:
                    continue
                position += 1
                s.add(Chapter(book_id=book_id, position=position,
                              volume_number=volume.number, number=ch.number,
                              title=ch.title, content=ch.content,
                              word_count=count_words(ch.content)))
            s.commit()
        return position
