"""Import user-provided EPUB files into the library so they read (and get TTS,
progress, collections…) just like scraped books. Each spine document becomes a
reader chapter; title/author/cover are pulled from the EPUB metadata.

Imported HTML is untrusted, so chapter bodies are run through the same
sanitizer the scraper uses (strips scripts, event handlers, javascript: URLs).
"""

from __future__ import annotations

import hashlib
import logging
import os
import posixpath
import re
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import ebooklib
from bs4 import BeautifulSoup
from ebooklib import epub
from sqlmodel import Session

from .models import Chapter, Volume
from .scraper.parser import sanitize, count_words

logger = logging.getLogger(__name__)

_IMG_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}
_MEDIA_EXT = {
    "image/jpeg": ".jpg", "image/jpg": ".jpg", "image/png": ".png",
    "image/webp": ".webp", "image/gif": ".gif", "image/svg+xml": ".svg",
}
# Placeholder scheme written into chapter <img> srcs at parse time; persist_epubs
# rewrites it to /api/books/{id}/images/ once the book id is known.
IMG_SENTINEL = "na-image:"


@dataclass
class ImportedChapter:
    title: str
    content: str  # sanitized HTML


@dataclass
class ImportedImage:
    name: str          # "{hash}{ext}" — the stored filename
    data: bytes
    content_type: str


@dataclass
class ImportedEpub:
    title: str
    author: str
    cover_bytes: Optional[bytes]
    cover_ext: str
    chapters: List[ImportedChapter]
    images: Dict[str, ImportedImage] = field(default_factory=dict)  # name -> image


def _meta(book: epub.EpubBook, ns: str, name: str) -> Optional[str]:
    try:
        vals = book.get_metadata(ns, name)
    except Exception:  # noqa: BLE001
        return None
    if vals:
        v = vals[0][0]
        if isinstance(v, str) and v.strip():
            return v.strip()
    return None


def _ext_from(item) -> str:
    ext = os.path.splitext(getattr(item, "file_name", "") or "")[1].lower()
    return ext if ext in _IMG_EXTS else ".jpg"


def _find_cover(book: epub.EpubBook) -> Tuple[Optional[bytes], str]:
    # 1. items explicitly flagged as the cover
    for item in book.get_items_of_type(ebooklib.ITEM_COVER):
        return item.get_content(), _ext_from(item)
    # 2. the OPF <meta name="cover" content="id"> pointer
    try:
        meta = book.get_metadata("OPF", "cover")
        if meta:
            it = book.get_item_with_id(meta[0][1].get("content"))
            if it is not None:
                return it.get_content(), _ext_from(it)
    except Exception:  # noqa: BLE001
        pass
    # 3. fall back to the first image
    for item in book.get_items_of_type(ebooklib.ITEM_IMAGE):
        return item.get_content(), _ext_from(item)
    return None, ".jpg"


def _img_ext(item) -> str:
    ext = os.path.splitext(getattr(item, "file_name", "") or "")[1].lower()
    if ext in _IMG_EXTS or ext == ".svg":
        return ext
    return _MEDIA_EXT.get((getattr(item, "media_type", "") or "").lower(), ".jpg")


def _epub_images(book: epub.EpubBook) -> Dict[str, object]:
    """Map each embedded image's normalized in-EPUB path to its item."""
    out: Dict[str, object] = {}
    for item in book.get_items_of_type(ebooklib.ITEM_IMAGE):
        name = (getattr(item, "file_name", "") or "").lstrip("/")
        if name:
            out[posixpath.normpath(name)] = item
    return out


def _rewrite_images(node, chapter_path: str, image_items: Dict[str, object],
                    collected: Dict[str, ImportedImage]) -> None:
    """Point each <img> at a stored copy of the EPUB image (deduplicated by
    content hash). Unresolvable/external images are left for the reader to
    handle (drop or keep absolute URLs)."""
    base_dir = posixpath.dirname((chapter_path or "").lstrip("/"))
    for img in node.find_all("img"):
        src = (img.get("src") or "").strip()
        if not src or src.startswith(("http://", "https://", "data:")):
            continue  # external/inline — leave as-is
        clean = src.split("#")[0].split("?")[0]
        resolved = posixpath.normpath(posixpath.join(base_dir, clean))
        item = image_items.get(resolved)
        if item is None:  # fall back to a basename match
            base = posixpath.basename(resolved)
            item = next((v for k, v in image_items.items()
                         if posixpath.basename(k) == base), None)
        if item is None:
            del img["src"]  # broken reference — reader will drop it
            continue
        data = item.get_content()
        name = f"{hashlib.sha256(data).hexdigest()[:16]}{_img_ext(item)}"
        collected.setdefault(name, ImportedImage(
            name=name, data=data,
            content_type=(getattr(item, "media_type", "") or "image/jpeg"),
        ))
        img["src"] = IMG_SENTINEL + name


def _chapter_title(soup: BeautifulSoup, fallback: str) -> str:
    t = soup.find("title")
    if t and t.get_text(strip=True):
        return t.get_text(strip=True)
    for h in ("h1", "h2", "h3"):
        el = soup.find(h)
        if el and el.get_text(strip=True):
            return el.get_text(strip=True)[:200]
    return fallback


def parse_epub(path: str) -> ImportedEpub:
    """Parse an EPUB file into title/author/cover + ordered sanitized chapters."""
    book = epub.read_epub(path)
    title = _meta(book, "DC", "title") or Path(path).stem
    author = _meta(book, "DC", "creator") or "Unknown Author"
    cover_bytes, cover_ext = _find_cover(book)
    image_items = _epub_images(book)

    chapters: List[ImportedChapter] = []
    images: Dict[str, ImportedImage] = {}
    for entry in book.spine:
        idref = entry[0] if isinstance(entry, (tuple, list)) else entry
        linear = entry[1] if isinstance(entry, (tuple, list)) and len(entry) > 1 else "yes"
        if str(linear).lower() == "no":
            continue  # non-linear content (e.g. the nav document)
        item = book.get_item_with_id(idref)
        if item is None or item.get_type() != ebooklib.ITEM_DOCUMENT:
            continue
        if isinstance(item, epub.EpubNav) or "nav" in (getattr(item, "properties", None) or []):
            continue  # EPUB3 navigation document, not a chapter
        soup = BeautifulSoup(item.get_content().decode("utf-8", "ignore"), "html.parser")
        body = soup.find("body") or soup
        sanitize(body)
        _rewrite_images(body, getattr(item, "file_name", ""), image_items, images)
        html = body.decode_contents().strip()
        if not BeautifulSoup(html, "html.parser").get_text(strip=True):
            continue  # blank page
        chapters.append(ImportedChapter(
            title=_chapter_title(soup, f"Chapter {len(chapters) + 1}"),
            content=html,
        ))

    if not chapters:
        raise ValueError("No readable chapters found in this EPUB")
    return ImportedEpub(title, author, cover_bytes, cover_ext, chapters, images)


def make_slug(title: str) -> str:
    base = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:50] or "epub"
    return f"{base}-{uuid.uuid4().hex[:8]}"


def save_cover(cover_bytes: Optional[bytes], cover_ext: str, slug: str,
               cover_dir: str) -> Optional[str]:
    if not cover_bytes:
        return None
    Path(cover_dir).mkdir(parents=True, exist_ok=True)
    safe = re.sub(r"[^A-Za-z0-9_-]", "_", slug)
    path = Path(cover_dir) / f"{safe}{cover_ext}"
    path.write_bytes(cover_bytes)
    return str(path)


def save_images(images: Dict[str, ImportedImage], book_id: int,
                image_dir: str) -> None:
    """Write an EPUB's images under the book's image dir, deduplicated by their
    hash-based filename (so a repeated ornament is stored once)."""
    if not images:
        return
    dest = Path(image_dir) / str(book_id)
    dest.mkdir(parents=True, exist_ok=True)
    for img in images.values():
        path = dest / img.name
        if not path.exists():
            path.write_bytes(img.data)


def persist_epubs(session: Session, book_id: int, parsed: List[ImportedEpub],
                  start_position: int, start_volume: int,
                  image_dir: Optional[str] = None) -> None:
    """Append parsed EPUBs to a book as volumes of chapters, continuing the
    existing position/volume numbering. Illustrations are stored under
    image_dir and their <img> srcs resolved to /api/books/{id}/images/…."""
    img_base = f"/api/books/{book_id}/images/"
    position = start_position
    vol_no = start_volume - 1
    for pe in parsed:
        vol_no += 1
        if image_dir:
            save_images(pe.images, book_id, image_dir)
        session.add(Volume(
            book_id=book_id, number=vol_no,
            title=pe.title or f"Volume {vol_no}",
            path="", chapter_count=len(pe.chapters), size_bytes=0,
        ))
        for ch in pe.chapters:
            position += 1
            content = ch.content.replace(IMG_SENTINEL, img_base)
            session.add(Chapter(
                book_id=book_id, position=position, volume_number=vol_no,
                number="", title=ch.title, content=content,
                word_count=count_words(content),
            ))
    session.commit()
