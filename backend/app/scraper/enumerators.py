"""Chapter enumeration strategies.

Enumeration (how chapter URLs are discovered) is decoupled from extraction so a
site can use whichever strategy fits:

- ``paginated``  - walk numbered chapter-list pages (the original behavior).
- ``next_link``  - follow each chapter's "next" link; handles opaque/random
  slugs since URLs are never constructed. Inherently serial.
"""

from __future__ import annotations

import json
import logging
from typing import Callable, List, Optional

from .errors import ScraperError
from .fetcher import AsyncFetcher
from .models import Book, Chapter
from .parser import (dig, find_link, find_next_link, find_next_link_generic,
                     find_first_chapter_generic, parse_chapter_content_generic,
                     parse_chapter_list, parse_title, parse_title_generic)
from .site_profile import SiteProfile

logger = logging.getLogger(__name__)

ProgressCb = Optional[Callable[[str, dict], None]]


def format_url(template: str, base_url: str, book: str, page=None) -> str:
    return template.format(base_url=base_url.rstrip("/"), book=book,
                           page="" if page is None else page)


async def enumerate_paginated(fetcher: AsyncFetcher, profile: SiteProfile,
                              book: Book, progress: ProgressCb = None) -> List[Chapter]:
    chapters: List[Chapter] = []
    seen_first_url: set[str] = set()
    seen_urls: set[str] = set()
    for page in range(1, profile.max_pages + 1):
        url = format_url(profile.list_url_template, profile.base_url, book.slug, page)
        # List pages are volatile (new chapters appear), so don't serve them
        # from cache; only chapter content is cached.
        if profile.list_method.upper() == "POST":
            data = {
                k: v.format(base_url=profile.base_url.rstrip("/"),
                            book=book.slug, page=page)
                for k, v in (profile.list_post_data or {}).items()
            }
            html = await fetcher.post_text(url, data)
        else:
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


async def _first_chapter_url(fetcher: AsyncFetcher, profile: SiteProfile,
                             book: Book) -> Optional[str]:
    """Start URL for next_link enumeration: either constructed from
    first_chapter_url_template, or (when the chapter-1 URL can't be constructed,
    e.g. it carries a title slug) resolved from an anchor on the book page."""
    if profile.first_chapter_selector:
        book_page = format_url(profile.book_page_url_template, profile.base_url, book.slug)
        html = await fetcher.get_text(book_page, use_cache=False)
        url = find_link(html, profile.first_chapter_selector, book_page)
        if not url:
            raise ScraperError(
                f"first_chapter_selector '{profile.first_chapter_selector}' "
                f"matched no chapter link on {book_page}")
        return url
    return format_url(profile.first_chapter_url_template, profile.base_url, book.slug)


async def enumerate_next_link(fetcher: AsyncFetcher, profile: SiteProfile,
                              book: Book, progress: ProgressCb = None) -> List[Chapter]:
    chapters: List[Chapter] = []
    url = await _first_chapter_url(fetcher, profile, book)
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


async def enumerate_json_api(fetcher: AsyncFetcher, profile: SiteProfile,
                             book: Book, progress: ProgressCb = None) -> List[Chapter]:
    """Read the whole chapter list from a JSON detail endpoint. The list order
    is the reading order, so the 1-based index is the chapter number and each
    chapter's content URL is built from chapter_url_template."""
    detail_url = format_url(profile.list_url_template, profile.base_url, book.slug)
    text = await fetcher.get_text(detail_url, use_cache=False)
    try:
        data = json.loads(text)
    except ValueError as e:
        raise ScraperError(f"book detail was not valid JSON: {e}")
    entries = dig(data, profile.json_chapters_path)
    if not isinstance(entries, list) or not entries:
        return []
    chapters: List[Chapter] = []
    for index, entry in enumerate(entries, start=1):
        if isinstance(entry, dict):
            key = profile.json_chapter_title_key or "name"
            title = str(entry.get(key) or "").strip()
        else:
            title = str(entry or "").strip()
        url = profile.chapter_url_template.format(
            base_url=profile.base_url.rstrip("/"), book=book.slug, number=index)
        chapters.append(Chapter(number=str(index), title=title, url=url))
    if progress:
        progress("enumerating", {"found": len(chapters)})
    return chapters


async def enumerate_sequential(fetcher: AsyncFetcher, profile: SiteProfile,
                               book: Book, progress: ProgressCb = None) -> List[Chapter]:
    """Numeric chapter URLs (``chapter_url_template`` with ``{number}``) walked
    1, 2, 3, … until a fetch fails — a 404 marks the end of the book. For sites
    with no chapter-list page and no next-links, but predictable sequential URLs.
    Each page is cached, so the content pass reuses it (no double fetch)."""
    chapters: List[Chapter] = []
    for n in range(1, profile.max_pages + 1):
        url = profile.chapter_url_template.format(
            base_url=profile.base_url.rstrip("/"), book=book.slug, number=n)
        try:
            html = await fetcher.get_text(url)  # cached: reused by the content pass
        except ScraperError:
            break  # out of range -> past the last chapter
        chapters.append(Chapter(number=str(n), title=parse_title(html, profile), url=url))
        if progress:
            progress("enumerating", {"found": len(chapters)})
    return chapters


async def enumerate_generic(fetcher: AsyncFetcher, profile: SiteProfile,
                            book: Book, progress: ProgressCb = None) -> List[Chapter]:
    """Profile-less enumeration: start at the pasted URL (jump to chapter 1 if it's
    a TOC/novel page), then follow heuristic 'next chapter' links. Inherently serial."""
    start = profile.start_url or profile.base_url
    first_html = await fetcher.get_text(start)  # cached: reused by the content pass
    # If the start page has little body text but a first-chapter link, it's a TOC.
    try:
        body_len = len(parse_chapter_content_generic(first_html))
    except ScraperError:
        body_len = 0
    if body_len < 400:
        first = find_first_chapter_generic(first_html, start)
        if first and first != start:
            start = first

    chapters: List[Chapter] = []
    url = start
    seen: set[str] = set()
    for _ in range(profile.max_pages):
        if url in seen:
            logger.warning("generic next-link loop at %s; stopping enumeration", url)
            break
        seen.add(url)
        html = await fetcher.get_text(url)  # cached: reused by the content pass
        chapters.append(Chapter(
            number=str(len(chapters) + 1),
            title=parse_title_generic(html, url),
            url=url,
        ))
        if progress:
            progress("enumerating", {"found": len(chapters)})
        nxt = find_next_link_generic(html, url)
        if not nxt or nxt in seen:
            break
        url = nxt
    return chapters


_STRATEGIES = {
    "paginated": enumerate_paginated,
    "next_link": enumerate_next_link,
    "json_api": enumerate_json_api,
    "sequential": enumerate_sequential,
    "generic": enumerate_generic,
}


async def enumerate_chapters(fetcher: AsyncFetcher, profile: SiteProfile,
                             book: Book, progress: ProgressCb = None) -> List[Chapter]:
    strategy = _STRATEGIES.get(profile.enumeration)
    if strategy is None:  # validated by SiteProfile, but guard anyway
        raise ValueError(f"No enumerator for strategy '{profile.enumeration}'")
    chapters = await strategy(fetcher, profile, book, progress)
    if profile.reverse_chapters:
        # TOC listed newest-first -> flip to oldest-first reading order.
        chapters.reverse()
    return chapters
