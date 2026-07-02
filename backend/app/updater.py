"""Optional background auto-update: periodically re-scrape books to pull new
chapters. Disabled unless NOVELSCRAPER_AUTO_UPDATE_HOURS > 0. The manual per-book
"Update" button (POST /books/{id}/update) works regardless of this setting.

Re-scraping reuses the normal job pipeline; the fetcher's disk cache means only
new/changed pages hit the network. Rating, collections and reading progress live
on the book row and survive the re-scrape.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

from sqlmodel import Session, select

from .jobs.manager import DuplicateJobError, JobManager
from .models import Book
from .schemas import JobCreate
from .store import get_auto_update_hours

logger = logging.getLogger(__name__)

# How often the loop wakes to check whether any book is due; the actual update
# cadence is the configured auto_update_hours (read fresh each wake, so changing
# it in Settings takes effect within this window).
_CHECK_INTERVAL_SECONDS = 900


def _aware(dt: datetime | None) -> datetime | None:
    """SQLite round-trips datetimes as naive; treat them as UTC for comparison."""
    if dt is None:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def _queue_due_updates(manager: JobManager, engine, hours: int) -> None:
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)
    with Session(engine) as s:
        books = s.exec(select(Book).where(Book.source_url.is_not(None))).all()
    queued = 0
    for book in books:
        updated = _aware(book.updated_at)
        if updated is not None and updated > cutoff:
            continue  # scraped recently enough
        try:
            manager.submit(JobCreate(url=book.source_url), incremental=True)
            queued += 1
        except DuplicateJobError:
            pass  # already updating
        except Exception:  # noqa: BLE001 — one bad book shouldn't stop the pass
            logger.exception("auto-update: submit failed for book %s", book.id)
    if queued:
        logger.info("auto-update queued %d book(s)", queued)


async def auto_update_loop(manager: JobManager, engine) -> None:
    await asyncio.sleep(60)  # let startup settle before the first pass
    while True:
        try:
            hours = get_auto_update_hours()  # re-read so Settings changes apply
            if hours > 0:
                _queue_due_updates(manager, engine, hours)
        except Exception:  # noqa: BLE001
            logger.exception("auto-update pass failed")
        await asyncio.sleep(_CHECK_INTERVAL_SECONDS)
