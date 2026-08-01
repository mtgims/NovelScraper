"""Top-level scrape orchestration: enumerate, fetch content, package into EPUBs.

Content is fetched and written one volume at a time. This bounds peak memory to
a single volume's chapters and produces EPUBs incrementally, while still
fetching the chapters *within* a volume concurrently (subject to the fetcher's
concurrency cap and rate limiter).
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path
from typing import Callable, List, Optional
from urllib.parse import urljoin, urlparse

from .config import ScraperConfig
from .enumerators import format_url, enumerate_chapters
from .errors import ScraperError
from .fetcher import AsyncFetcher
from .metadata import extract_metadata
from .models import Book, Chapter, ScrapeResult, VolumeResult
from .parser import dig, parse_chapter_content, parse_json_content
from .site_profile import SiteProfile

logger = logging.getLogger(__name__)

ProgressCb = Optional[Callable[[str, dict], None]]
# Called after each volume's EPUB is written, with the (metadata-enriched) book,
# that volume's result and its chapters. Lets a caller persist content
# incrementally without breaking the per-volume memory bound.
VolumeCb = Optional[Callable[[Book, VolumeResult, List[Chapter]], None]]

_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def _cover_ext(url: str) -> str:
    parsed = urlparse(url)
    ext = Path(parsed.path).suffix.lower()
    if ext in _IMAGE_EXTS:
        return ext
    # Image endpoints sometimes carry the type in a ?format= query param
    # (e.g. /cover?format=webp) rather than a file extension.
    m = re.search(r"format=(webp|png|jpe?g|gif)", parsed.query or "", re.IGNORECASE)
    if m:
        fmt = m.group(1).lower()
        return ".jpg" if fmt in ("jpg", "jpeg") else f".{fmt}"
    return ".jpg"


async def _save_cover(fetcher: AsyncFetcher, book: Book, cover_url: str,
                      cover_dir: str) -> None:
    try:
        data = await fetcher.get_bytes(cover_url)
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", book.slug).strip("_") or "cover"
        path = Path(cover_dir) / f"{safe}{_cover_ext(cover_url)}"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        book.cover_path = str(path)
    except (ScraperError, OSError) as e:
        logger.warning("cover fetch/save failed for %s: %s", cover_url, e)


async def _load_metadata(fetcher: AsyncFetcher, profile: SiteProfile, book: Book,
                         source_url: str, cover_dir: Optional[str]) -> None:
    """Best-effort: enrich `book` with title/author and download a cover.
    Never fails the scrape."""
    if profile.enumeration == "json_api":
        await _load_metadata_json(fetcher, profile, book, cover_dir)
        return
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
        await _save_cover(fetcher, book, meta.cover_url, cover_dir)


async def _load_metadata_json(fetcher: AsyncFetcher, profile: SiteProfile,
                              book: Book, cover_dir: Optional[str]) -> None:
    """Metadata for json_api sites: read title/author/cover from the same JSON
    detail endpoint the chapter list comes from."""
    try:
        detail_url = format_url(profile.list_url_template, profile.base_url, book.slug)
        data = json.loads(await fetcher.get_text(detail_url, use_cache=False))
    except (ScraperError, ValueError) as e:
        logger.warning("could not fetch book detail for %s: %s", book.slug, e)
        return
    if profile.json_title_path:
        title = dig(data, profile.json_title_path)
        if title:
            book.title = str(title).strip()
    if profile.json_author_path:
        author = dig(data, profile.json_author_path)
        if author:
            book.author = str(author).strip()
    if profile.json_cover_path and cover_dir:
        cover = dig(data, profile.json_cover_path)
        if cover:
            await _save_cover(fetcher, book, urljoin(profile.base_url, str(cover)), cover_dir)


async def _fetch_content(fetcher: AsyncFetcher, profile: SiteProfile,
                         chapter: Chapter, progress: ProgressCb) -> Chapter:
    try:
        body = await fetcher.get_text(chapter.url)
        if profile.json_content_path:
            chapter.content = parse_json_content(body, profile)
        else:
            chapter.content = parse_chapter_content(body, profile)
    except ScraperError as e:
        # A single bad chapter shouldn't abort the whole book; record and skip.
        logger.warning("skipping chapter %s (%s): %s",
                       chapter.number or "?", chapter.url, e)
        chapter.content = None
    if progress:
        progress("fetched", {"url": chapter.url, "ok": chapter.has_content})
    return chapter


def _volumes(chapters: List[Chapter], size: int, start_position: int = 0):
    """Group chapters into volumes by GLOBAL reading position (1-based), so an
    incremental update keeps filling the last (possibly partial) volume rather
    than starting a fresh volume for every batch of new chapters. ``size`` is the
    chapters-per-volume; ``start_position`` is the highest position already saved
    (0 for a fresh scrape). Yields (volume_number, chapters_in_that_volume)."""
    i = 0
    total = len(chapters)
    while i < total:
        pos = start_position + i + 1            # global position of chapters[i]
        vol_no = (pos - 1) // size + 1
        end_of_vol = vol_no * size              # last global position in this volume
        take = min(total, i + (end_of_vol - pos + 1))
        yield vol_no, chapters[i:take]
        i = take


async def scrape_book(book_slug: str, profile: SiteProfile, config: ScraperConfig,
                      progress: ProgressCb = None,
                      on_volume: VolumeCb = None,
                      source_url: Optional[str] = None,
                      start_position: int = 0) -> ScrapeResult:
    """Scrape a book. For an incremental update, pass ``start_position`` (the
    highest chapter position already saved): enumeration still runs to discover
    the full list, but only chapters past ``start_position`` are fetched and
    packaged. Volumes are numbered by global position (see ``_volumes``), so new
    chapters continue filling the last existing volume rather than starting a new
    one — set ``config.chapters_per_volume`` to the book's existing volume size."""
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
                new_chapters, config.chapters_per_volume, start_position):
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
