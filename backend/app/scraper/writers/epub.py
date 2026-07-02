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
from pathlib import Path
from typing import List, Optional, Set

from ebooklib import epub

_MEDIA_TYPES = {
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
    ".webp": "image/webp", ".gif": "image/gif", ".svg": "image/svg+xml",
}


def _safe_filename(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", name).strip("_")
    return cleaned or "item"


def _localize_images(content: str, api_prefix: str, available: Set[str],
                     used: Set[str]) -> str:
    """Rewrite stored illustration srcs (/api/books/{id}/images/NAME) to an
    in-EPUB relative path (images/NAME) so the download is self-contained.
    Only rewrites images that actually exist on disk; records those in ``used``."""
    pattern = re.compile(r'src="' + re.escape(api_prefix) + r'([A-Za-z0-9]+\.[A-Za-z0-9]+)"')

    def repl(match: re.Match) -> str:
        name = match.group(1)
        if name in available:
            used.add(name)
            return f'src="images/{name}"'
        return match.group(0)

    return pattern.sub(repl, content)


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
                     chapters: List, volume_number: int,
                     book_id: Optional[int] = None,
                     image_dir: Optional[str] = None) -> bytes:
    """Build a volume's EPUB in memory. ``chapters`` is any sequence of objects
    with ``content``, ``number`` and ``title`` attributes (scraped or DB rows).

    When ``book_id`` and ``image_dir`` are given, imported illustrations
    referenced as /api/books/{id}/images/… are embedded into the EPUB and their
    srcs rewritten to relative paths, so the download works offline."""
    epub_book = epub.EpubBook()
    vol_title = f"{title} - Volume {volume_number}"
    epub_book.set_identifier(_safe_filename(f"{slug}-vol{volume_number}"))
    epub_book.set_title(vol_title)
    epub_book.set_language(language or "en")
    epub_book.add_author(author or "Unknown Author")

    # Discover this book's stored illustrations so referenced ones can be embedded.
    available: Set[str] = set()
    api_prefix = ""
    if book_id is not None and image_dir is not None:
        api_prefix = f"/api/books/{book_id}/images/"
        book_images = Path(image_dir) / str(book_id)
        if book_images.is_dir():
            available = {p.name for p in book_images.iterdir() if p.is_file()}
    used_images: Set[str] = set()

    epub_chapters = []
    for position, chapter in enumerate(chapters, start=1):
        if not chapter.content:
            continue
        heading = _heading(chapter.number, chapter.title, position)
        body = chapter.content
        if available:
            body = _localize_images(body, api_prefix, available, used_images)
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
            f"<h1>{html.escape(heading)}</h1>\n{body}"
            "</body></html>"
        )
        epub_book.add_item(item)
        epub_chapters.append(item)

    # Embed the illustrations actually referenced (once each).
    for name in sorted(used_images):
        path = Path(image_dir) / str(book_id) / name
        try:
            data = path.read_bytes()
        except OSError:
            continue
        media = _MEDIA_TYPES.get(os.path.splitext(name)[1].lower(), "image/jpeg")
        epub_book.add_item(epub.EpubItem(
            uid=f"img_{_safe_filename(name)}", file_name=f"images/{name}",
            media_type=media, content=data,
        ))

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
