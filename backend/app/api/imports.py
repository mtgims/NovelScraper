"""EPUB import endpoints.

Lets the user turn their own EPUB file(s) into a library book that reads (TTS,
progress, collections, downloads) exactly like a scraped one. Multiple EPUBs can
be uploaded at once — and more appended later — since a novel is often split
across several files. Appending is only allowed for imported (user-created)
books, never scraped ones.
"""

from __future__ import annotations

import logging
import os
import tempfile
from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from sqlmodel import Session, func, select
from starlette.concurrency import run_in_threadpool

from ..db import get_session
from ..importer import (
    ImportedEpub,
    make_slug,
    parse_epub,
    persist_epubs,
    save_cover,
)
from ..models import Book, Chapter, Volume
from ..schemas import BookRead
from ..settings import settings
from .books import _book_read

logger = logging.getLogger(__name__)

router = APIRouter()

MAX_EPUB_BYTES = 100 * 1024 * 1024  # 100 MB per file — guards against zip bombs


async def _parse_uploads(files: List[UploadFile]) -> List[ImportedEpub]:
    """Stream each upload to a temp file (size-capped) and parse it. Parsing
    happens up front so a bad file in the batch fails before anything is written
    to the database."""
    if not files:
        raise HTTPException(status_code=400, detail="No files uploaded")
    parsed: List[ImportedEpub] = []
    for f in files:
        name = f.filename or "upload.epub"
        if not name.lower().endswith(".epub"):
            raise HTTPException(status_code=400, detail=f"{name!r} is not an .epub file")
        fd, tmp = tempfile.mkstemp(suffix=".epub")
        size = 0
        try:
            with os.fdopen(fd, "wb") as out:
                while chunk := await f.read(1 << 20):
                    size += len(chunk)
                    if size > MAX_EPUB_BYTES:
                        raise HTTPException(
                            status_code=413,
                            detail=f"{name!r} exceeds the 100 MB limit",
                        )
                    out.write(chunk)
            try:
                # Parsing (unzip + sanitize every chapter) is sync CPU work;
                # run it off the event loop so it can't block other requests.
                parsed.append(await run_in_threadpool(parse_epub, tmp))
            except HTTPException:
                raise
            except Exception as exc:  # noqa: BLE001 — surface a clean 400
                raise HTTPException(
                    status_code=400,
                    detail=f"Could not read {name!r}: {exc}",
                )
        finally:
            os.unlink(tmp)
    return parsed


@router.post("/import", response_model=BookRead, status_code=201)
async def import_epubs(files: List[UploadFile] = File(...),
                       session: Session = Depends(get_session)):
    """Create a new imported library book from one or more EPUBs. Title, author
    and cover come from the first file; each file becomes a volume."""
    parsed = await _parse_uploads(files)
    first = parsed[0]
    slug = make_slug(first.title)
    book = Book(
        slug=slug, site="import", imported=True,
        title=first.title, author=first.author, language="en",
    )
    book.cover_path = save_cover(
        first.cover_bytes, first.cover_ext, slug, str(settings.cover_dir)
    )
    session.add(book)
    session.commit()
    session.refresh(book)
    persist_epubs(session, book.id, parsed, start_position=0, start_volume=1)
    session.refresh(book)
    return _book_read(session, book)


@router.post("/books/{book_id}/import", response_model=BookRead)
async def add_epubs(book_id: int, files: List[UploadFile] = File(...),
                    session: Session = Depends(get_session)):
    """Append more EPUB(s) as new volumes to an existing imported book."""
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="Book not found")
    if not book.imported:
        raise HTTPException(
            status_code=400,
            detail="EPUBs can only be added to imported novels, not scraped ones",
        )
    parsed = await _parse_uploads(files)
    start_position = session.exec(
        select(func.max(Chapter.position)).where(Chapter.book_id == book_id)
    ).one() or 0
    start_volume = (session.exec(
        select(func.max(Volume.number)).where(Volume.book_id == book_id)
    ).one() or 0) + 1
    persist_epubs(session, book_id, parsed, start_position, start_volume)
    book.updated_at = datetime.now(timezone.utc)
    session.add(book)
    session.commit()
    session.refresh(book)
    return _book_read(session, book)
