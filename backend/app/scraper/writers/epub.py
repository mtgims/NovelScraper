"""EPUB output writer.

Chapters without content are skipped. Filenames use the chapter's position in
the volume (not the scraped chapter number) to avoid collisions when numbers
are missing or duplicated.
"""

from __future__ import annotations

import html
import re
from pathlib import Path
from typing import List

from ebooklib import epub

from ..models import Book, Chapter, VolumeResult


def _safe_filename(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", name).strip("_")
    return cleaned or "item"


def _heading(chapter: Chapter, position: int) -> str:
    parts = []
    if chapter.number:
        parts.append(f"Chapter {chapter.number}")
    if chapter.title:
        parts.append(chapter.title)
    return ": ".join(parts) or f"Chapter {position}"


def write_epub(book: Book, chapters: List[Chapter], volume_number: int,
               output_dir: str) -> VolumeResult:
    epub_book = epub.EpubBook()
    vol_title = f"{book.display_title()} - Volume {volume_number}"
    epub_book.set_identifier(_safe_filename(f"{book.slug}-vol{volume_number}"))
    epub_book.set_title(vol_title)
    epub_book.set_language(book.language)
    epub_book.add_author(book.author)

    epub_chapters = []
    for position, chapter in enumerate(chapters, start=1):
        if not chapter.content:
            continue
        heading = _heading(chapter, position)
        item = epub.EpubHtml(
            title=heading,
            file_name=f"chapter_{position:04d}.xhtml",
            lang=book.language,
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

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    filepath = out_dir / f"{_safe_filename(book.slug)}-volume-{volume_number}.epub"
    epub.write_epub(str(filepath), epub_book)

    return VolumeResult(
        number=volume_number,
        title=vol_title,
        path=str(filepath),
        chapter_count=len(epub_chapters),
    )
