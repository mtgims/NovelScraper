"""Top-level scrape orchestration: enumerate, fetch content, package into EPUBs.

Content is fetched and written one volume at a time. This bounds peak memory to
a single volume's chapters and produces EPUBs incrementally, while still
fetching the chapters *within* a volume concurrently (subject to the fetcher's
concurrency cap and rate limiter).
"""

from __future__ import annotations

import asyncio
import logging
import re
from pathlib import Path
from typing import Callable, List, Optional, Tuple
from urllib.parse import urlparse

from .config import ScraperConfig
from .enumerators import enumerate_chapters
from .errors import ScraperError
from .fetcher import AsyncFetcher
from .metadata import extract_metadata
from .models import Book, Chapter, ScrapeResult, VolumeResult
from .parser import parse_chapter_content
from .site_profile import SiteProfile

logger = logging.getLogger(__name__)

ProgressCb = Optional[Callable[[str, dict], None]]
# Called after each volume's EPUB is written, with the (metadata-enriched) book,
# that volume's result and its chapters. Lets a caller persist content
# incrementally without breaking the per-volume memory bound.
VolumeCb = Optional[Callable[[Book, VolumeResult, List[Chapter]], None]]

_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def _cover_ext(url: str) -> str:
    ext = Path(urlparse(url).path).suffix.lower()
    return ext if ext in _IMAGE_EXTS else ".jpg"


async def _load_metadata(fetcher: AsyncFetcher, profile: SiteProfile, book: Book,
                         source_url: str, cover_dir: Optional[str]) -> None:
    """Best-effort: enrich `book` with title/author and download a cover.
    Never fails the scrape."""
    try:
        html = await fetcher.get_text(source_url, use_cache=False)
    except ScraperError as e:
        logger.warning("could not fetch book page %s: %s", source_url, e)
        return
    meta = extract_metadata(
        html, source_url,
        title_selector=profile.book_title_selector,
        author_selector=profile.book_author_selector,
        cover_selector=profile.book_cover_selector,
    )
    if meta.title:
        book.title = meta.title
    if meta.author:
        book.author = meta.author
    if meta.cover_url and cover_dir:
        try:
            data = await fetcher.get_bytes(meta.cover_url)
            safe = re.sub(r"[^A-Za-z0-9._-]", "_", book.slug).strip("_") or "cover"
            path = Path(cover_dir) / f"{safe}{_cover_ext(meta.cover_url)}"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            book.cover_path = str(path)
        except (ScraperError, OSError) as e:
            logger.warning("cover fetch/save failed for %s: %s", meta.cover_url, e)


async def _fetch_content(fetcher: AsyncFetcher, profile: SiteProfile,
                         chapter: Chapter, progress: ProgressCb) -> Chapter:
    try:
        html = await fetcher.get_text(chapter.url)
        chapter.content = parse_chapter_content(html, profile)
    except ScraperError as e:
        # A single bad chapter shouldn't abort the whole book; record and skip.
        logger.warning("skipping chapter %s (%s): %s",
                       chapter.number or "?", chapter.url, e)
        chapter.content = None
    if progress:
        progress("fetched", {"url": chapter.url, "ok": chapter.has_content})
    return chapter


def _volumes(chapters: List[Chapter], size: int, start_no: int = 1):
    for index in range(0, len(chapters), size):
        yield start_no + index // size, chapters[index:index + size]


async def scrape_book(book_slug: str, profile: SiteProfile, config: ScraperConfig,
                      progress: ProgressCb = None,
                      on_volume: VolumeCb = None,
                      source_url: Optional[str] = None,
                      start_position: int = 0,
                      start_volume: int = 1) -> ScrapeResult:
    """Scrape a book. For an incremental update, pass ``start_position`` (the
    highest chapter position already saved) and ``start_volume`` (next volume
    number): enumeration still runs to discover the full list, but only chapters
    past ``start_position`` are fetched and packaged, as volumes numbered from
    ``start_volume``."""
    book = Book(slug=book_slug)
    volumes: List[VolumeResult] = []
    skipped = 0

    async with AsyncFetcher(config) as fetcher:
        if source_url:
            await _load_metadata(fetcher, profile, book, source_url, config.cover_dir)
        logger.info("enumerating chapters for '%s'", book_slug)
        chapters = await enumerate_chapters(fetcher, profile, book, progress)
        if not chapters:
            raise ScraperError(f"No chapters found for '{book_slug}'")
        total = len(chapters)
        logger.info("found %d chapters", total)

        # Incremental update: enumeration returns chapters in reading order, so
        # the ones we already have are the first `start_position` entries — skip
        # them and only fetch/package what's past that.
        new_chapters = chapters[start_position:] if start_position > 0 else chapters
        if start_position > 0:
            logger.info("update: %d new chapter(s) past position %d",
                        len(new_chapters), start_position)
            if progress:
                progress("enumerating", {"found": total})

        for vol_no, vol_chapters in _volumes(
                new_chapters, config.chapters_per_volume, start_volume):
            tasks = [_fetch_content(fetcher, profile, ch, progress)
                     for ch in vol_chapters]
            fetched = await asyncio.gather(*tasks)
            skipped += sum(1 for ch in fetched if not ch.has_content)
            if not any(ch.has_content for ch in fetched):
                logger.warning("volume %d has no usable chapters; skipping", vol_no)
                continue
            if progress:
                progress("building", {"volume": vol_no, "chapters": len(fetched)})
            # EPUBs are built on demand at download time (from the stored
            # chapters), not written here — so a volume is just metadata now.
            volume = VolumeResult(
                number=vol_no,
                title=f"{book.display_title()} - Volume {vol_no}",
                path="",
                chapter_count=sum(1 for ch in fetched if ch.has_content),
            )
            volumes.append(volume)
            if on_volume is not None:
                on_volume(book, volume, fetched)

    return ScrapeResult(
        book=book,
        total_chapters=total,
        volumes=volumes,
        skipped_chapters=skipped,
    )
