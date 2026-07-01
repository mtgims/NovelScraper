"""Chapter enumeration strategies.

Enumeration (how chapter URLs are discovered) is decoupled from extraction so a
site can use whichever strategy fits:

- ``paginated``  - walk numbered chapter-list pages (the original behavior).
- ``next_link``  - follow each chapter's "next" link; handles opaque/random
  slugs since URLs are never constructed. Inherently serial.
"""

from __future__ import annotations

import logging
from typing import Callable, List, Optional

from .fetcher import AsyncFetcher
from .models import Book, Chapter
from .parser import find_next_link, parse_chapter_list, parse_title
from .site_profile import SiteProfile

logger = logging.getLogger(__name__)

ProgressCb = Optional[Callable[[str, dict], None]]


def _format_url(template: str, base_url: str, book: str, page=None) -> str:
    return template.format(base_url=base_url.rstrip("/"), book=book,
                           page="" if page is None else page)


async def enumerate_paginated(fetcher: AsyncFetcher, profile: SiteProfile,
                              book: Book, progress: ProgressCb = None) -> List[Chapter]:
    chapters: List[Chapter] = []
    seen_first_url: set[str] = set()
    seen_urls: set[str] = set()
    for page in range(1, profile.max_pages + 1):
        url = _format_url(profile.list_url_template, profile.base_url, book.slug, page)
        # List pages are volatile (new chapters appear), so don't serve them
        # from cache; only chapter content is cached.
        html = await fetcher.get_text(url, use_cache=False)
        page_chapters = parse_chapter_list(html, profile)
        if not page_chapters:
            break
        # Guard against a site that returns the same page for out-of-range
        # numbers, which would otherwise loop until max_pages.
        first_url = page_chapters[0].url
        if first_url in seen_first_url:
            logger.warning("page %d repeats earlier content; stopping enumeration", page)
            break
        seen_first_url.add(first_url)
        # De-duplicate by URL (some sites link the same chapter twice per row,
        # e.g. a title link and a timestamp link), keeping the first occurrence.
        for chapter in page_chapters:
            if chapter.url in seen_urls:
                continue
            seen_urls.add(chapter.url)
            chapters.append(chapter)
        if progress:
            progress("enumerating", {"page": page, "found": len(chapters)})
    return chapters


async def enumerate_next_link(fetcher: AsyncFetcher, profile: SiteProfile,
                              book: Book, progress: ProgressCb = None) -> List[Chapter]:
    chapters: List[Chapter] = []
    url: Optional[str] = _format_url(
        profile.first_chapter_url_template, profile.base_url, book.slug)
    seen: set[str] = set()
    for _ in range(profile.max_pages):
        if url in seen:
            logger.warning("next-link loop detected at %s; stopping enumeration", url)
            break
        seen.add(url)
        html = await fetcher.get_text(url)  # cached: reused by the content pass
        chapters.append(Chapter(
            number=str(len(chapters) + 1),
            title=parse_title(html, profile),
            url=url,
        ))
        if progress:
            progress("enumerating", {"found": len(chapters)})
        url = find_next_link(html, profile, url)
        if not url:
            break
    return chapters


_STRATEGIES = {
    "paginated": enumerate_paginated,
    "next_link": enumerate_next_link,
}


async def enumerate_chapters(fetcher: AsyncFetcher, profile: SiteProfile,
                             book: Book, progress: ProgressCb = None) -> List[Chapter]:
    strategy = _STRATEGIES.get(profile.enumeration)
    if strategy is None:  # validated by SiteProfile, but guard anyway
        raise ValueError(f"No enumerator for strategy '{profile.enumeration}'")
    return await strategy(fetcher, profile, book, progress)
