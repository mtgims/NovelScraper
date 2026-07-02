"""EPUB output builder.

EPUBs are built in memory on demand (at download time) from stored chapters
rather than saved to disk — the chapter text already lives in the database, so
storing the EPUB too would just duplicate it. Chapters without content are
skipped. Filenames use the chapter's position in the volume (not the scraped
chapter number) to avoid collisions when numbers are missing or duplicated.
"""

from __future__ import annotations

import html
import os
import re
import tempfile
from typing import List

from ebooklib import epub


def _safe_filename(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", name).strip("_")
    return cleaned or "item"


def epub_filename(slug: str, volume_number: int) -> str:
    return f"{_safe_filename(slug)}-volume-{volume_number}.epub"


def _heading(number: str, title: str, position: int) -> str:
    parts = []
    if number:
        parts.append(f"Chapter {number}")
    if title:
        parts.append(title)
    return ": ".join(parts) or f"Chapter {position}"


def build_epub_bytes(title: str, author: str, language: str, slug: str,
                     chapters: List, volume_number: int) -> bytes:
    """Build a volume's EPUB in memory. ``chapters`` is any sequence of objects
    with ``content``, ``number`` and ``title`` attributes (scraped or DB rows)."""
    epub_book = epub.EpubBook()
    vol_title = f"{title} - Volume {volume_number}"
    epub_book.set_identifier(_safe_filename(f"{slug}-vol{volume_number}"))
    epub_book.set_title(vol_title)
    epub_book.set_language(language or "en")
    epub_book.add_author(author or "Unknown Author")

    epub_chapters = []
    for position, chapter in enumerate(chapters, start=1):
        if not chapter.content:
            continue
        heading = _heading(chapter.number, chapter.title, position)
        item = epub.EpubHtml(
            title=heading,
            file_name=f"chapter_{position:04d}.xhtml",
            lang=language or "en",
        )
        # No XML prolog: ebooklib's nav/page-list generation parses the body
        # with lxml's HTML parser, which rejects a leading <?xml ?> declaration.
        item.content = (
            '<html xmlns="http://www.w3.org/1999/xhtml"><head>'
            f"<title>{html.escape(heading)}</title></head><body>"
            f"<h1>{html.escape(heading)}</h1>\n{chapter.content}"
            "</body></html>"
        )
        epub_book.add_item(item)
        epub_chapters.append(item)

    epub_book.toc = tuple(epub_chapters)
    epub_book.add_item(epub.EpubNcx())
    epub_book.add_item(epub.EpubNav())
    epub_book.spine = ["nav", *epub_chapters]

    # ebooklib writes to a path; use a temp file, read the bytes back, discard it.
    fd, tmp = tempfile.mkstemp(suffix=".epub")
    os.close(fd)
    try:
        epub.write_epub(tmp, epub_book)
        with open(tmp, "rb") as f:
            return f.read()
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass
