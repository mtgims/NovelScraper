"""Library endpoints: list/read scraped books, download volumes, delete."""

from __future__ import annotations

import io
import os
import re
import shutil
import tempfile
import zipfile
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from fastapi.responses import FileResponse, StreamingResponse
from sqlmodel import Session, delete, select

from ..db import get_session
from ..scraper.writers.epub import build_epub_bytes, epub_filename
from ..models import (
    ArchivedProgress,
    Book,
    BookCollectionLink,
    Chapter,
    Collection,
    ReadingProgress,
    User,
    Volume,
)
from ..services.library import book_read, books_read, has_cover
from ..services.reading import ensure_word_counts, progress_payload
from ..settings import settings
from ..tts import (
    DEFAULT_VOICE,
    build_chunks,
    chunk_to_text,
    encode_mp3,
    segment_blocks,
    segment_paragraphs,
    tts,
)
from ..jobs.manager import DuplicateJobError, JobManager
from .deps import get_current_user, get_manager
from ..schemas import (
    BookCollectionsUpdate,
    BookRead,
    BookReorder,
    BookUpdate,
    ChapterListItem,
    ChapterRead,
    JobCreate,
    JobRead,
    ProgressUpdate,
    ReadingProgressRead,
)

router = APIRouter()


def _write_atomic(path, data: bytes) -> None:
    """Write bytes via a temp file + os.replace, so a concurrent reader never sees
    a half-written file (two requests can race to synthesize the same chunk)."""
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def _owned_book(session: Session, book_id: int, user: User) -> Book:
    """Fetch a book the caller owns, or 404. Returning 404 (not 403) for books
    owned by someone else avoids leaking that the id exists."""
    book = session.get(Book, book_id)
    if book is None or book.user_id != user.id:
        raise HTTPException(status_code=404, detail="Book not found")
    return book


def _get_chapter(session: Session, book_id: int, position: int) -> Chapter:
    chapter = session.exec(
        select(Chapter).where(
            Chapter.book_id == book_id, Chapter.position == position
        )
    ).first()
    if chapter is None:
        raise HTTPException(status_code=404, detail="Chapter not found")
    return chapter


@router.get("", response_model=List[BookRead])
def list_books(user: User = Depends(get_current_user),
               session: Session = Depends(get_session)):
    # Manual order first (drag-to-reorder), newest first as a tiebreaker.
    books = session.exec(
        select(Book).where(Book.user_id == user.id)
        .order_by(Book.sort_order, Book.created_at.desc())
    ).all()
    return books_read(session, books)


@router.put("/{book_id}/collections", response_model=BookRead)
def set_book_collections(book_id: int, body: BookCollectionsUpdate,
                         user: User = Depends(get_current_user),
                         session: Session = Depends(get_session)):
    """Replace the set of collections a book belongs to (checkbox assignment)."""
    book = _owned_book(session, book_id, user)
    # Only the caller's own collections are valid targets (scoped by user_id).
    valid = set(session.exec(
        select(Collection.id).where(
            Collection.id.in_(body.collection_ids or [0]),
            Collection.user_id == user.id,
        )
    ).all())
    session.exec(
        delete(BookCollectionLink).where(BookCollectionLink.book_id == book_id)
    )
    for cid in valid:
        session.add(BookCollectionLink(book_id=book_id, collection_id=cid))
    session.commit()
    return book_read(session, book)


@router.post("/reorder", status_code=204)
def reorder_books(body: BookReorder, user: User = Depends(get_current_user),
                  session: Session = Depends(get_session)):
    """Assign a manual library order from a full list of book ids (front to back).
    Ids not present keep their existing sort_order (pushed after the ordered set)."""
    for index, book_id in enumerate(body.ordered_ids):
        book = session.get(Book, book_id)
        if book is not None and book.user_id == user.id:  # ignore ids not owned
            book.sort_order = index
            session.add(book)
    session.commit()


@router.get("/{book_id}", response_model=BookRead)
def get_book(book_id: int, user: User = Depends(get_current_user),
             session: Session = Depends(get_session)):
    return book_read(session, _owned_book(session, book_id, user))


@router.patch("/{book_id}", response_model=BookRead)
def update_book(book_id: int, body: BookUpdate,
                user: User = Depends(get_current_user),
                session: Session = Depends(get_session)):
    """Edit book metadata (currently just the rating)."""
    book = _owned_book(session, book_id, user)
    if body.rating is not None:
        book.rating = body.rating or None  # 0 clears the rating
    session.add(book)
    session.commit()
    session.refresh(book)
    return book_read(session, book)


@router.post("/{book_id}/update", response_model=JobRead, status_code=202)
async def update_book_chapters(book_id: int, user: User = Depends(get_current_user),
                               manager: JobManager = Depends(get_manager),
                               session: Session = Depends(get_session)):
    """Re-scrape the book from its original URL to pull in new chapters. Reuses
    the normal scrape pipeline; rating, collections and reading progress live on
    the book row and are preserved."""
    book = _owned_book(session, book_id, user)
    if not book.source_url:
        raise HTTPException(status_code=400, detail="No source URL to update from")
    try:
        return manager.submit(JobCreate(url=book.source_url), user_id=user.id,
                              incremental=True)
    except DuplicateJobError:
        raise HTTPException(status_code=409, detail="An update is already running")


@router.get("/{book_id}/chapters", response_model=List[ChapterListItem])
def list_chapters(book_id: int, user: User = Depends(get_current_user),
                  session: Session = Depends(get_session)):
    _owned_book(session, book_id, user)
    # Select only TOC columns — never load (potentially large) chapter content
    # just to list the table of contents.
    rows = session.exec(
        select(Chapter.position, Chapter.number, Chapter.title)
        .where(Chapter.book_id == book_id)
        .order_by(Chapter.position)
    ).all()
    return [
        ChapterListItem(position=p, number=n, title=t) for p, n, t in rows
    ]


@router.get("/{book_id}/chapters/{position}", response_model=ChapterRead)
def get_chapter(book_id: int, position: int, user: User = Depends(get_current_user),
                session: Session = Depends(get_session)):
    _owned_book(session, book_id, user)
    chapter = _get_chapter(session, book_id, position)
    has_next = session.exec(
        select(Chapter.id).where(
            Chapter.book_id == book_id, Chapter.position == position + 1
        )
    ).first() is not None
    return ChapterRead(
        position=chapter.position,
        number=chapter.number,
        title=chapter.title,
        content=chapter.content,
        has_prev=position > 1,
        has_next=has_next,
    )


@router.get("/{book_id}/progress", response_model=ReadingProgressRead)
def get_progress(book_id: int, user: User = Depends(get_current_user),
                 session: Session = Depends(get_session)):
    _owned_book(session, book_id, user)
    ensure_word_counts(session, book_id)
    prog = session.exec(
        select(ReadingProgress).where(ReadingProgress.book_id == book_id)
    ).first() or ReadingProgress(book_id=book_id)
    return progress_payload(session, book_id, prog)


@router.put("/{book_id}/progress", response_model=ReadingProgressRead)
def update_progress(book_id: int, body: ProgressUpdate,
                    user: User = Depends(get_current_user),
                    session: Session = Depends(get_session)):
    _owned_book(session, book_id, user)
    ensure_word_counts(session, book_id)
    prog = session.exec(
        select(ReadingProgress).where(ReadingProgress.book_id == book_id)
    ).first()
    if prog is None:
        prog = ReadingProgress(book_id=book_id)

    if body.reset:
        prog.read_positions = []
        prog.last_position = 1
        prog.scroll = 0.0
    if body.mark_all:
        positions = session.exec(
            select(Chapter.position).where(Chapter.book_id == book_id)
        ).all()
        prog.read_positions = sorted(positions)
    if body.last_position is not None:
        prog.last_position = body.last_position
    if body.scroll is not None:
        prog.scroll = body.scroll
    # Reassign the list (not mutate) so SQLAlchemy detects the JSON change.
    if body.mark_read is not None and body.mark_read not in prog.read_positions:
        prog.read_positions = prog.read_positions + [body.mark_read]
    if body.unmark_read is not None and body.unmark_read in prog.read_positions:
        prog.read_positions = [p for p in prog.read_positions if p != body.unmark_read]
    if body.mark_positions:
        prog.read_positions = sorted(set(prog.read_positions) | set(body.mark_positions))
    if body.unmark_positions:
        remove = set(body.unmark_positions)
        prog.read_positions = [p for p in prog.read_positions if p not in remove]
    prog.updated_at = datetime.now(timezone.utc)

    session.add(prog)
    session.commit()
    session.refresh(prog)
    return progress_payload(session, book_id, prog)


@router.get("/{book_id}/chapters/{position}/audio/manifest")
def audio_manifest(book_id: int, position: int,
                   voice: Optional[str] = None,
                   speed: float = Query(1.0, ge=0.5, le=2.0),
                   user: User = Depends(get_current_user),
                   session: Session = Depends(get_session)):
    # Segmentation is pure text processing and independent of the server TTS
    # model, so the manifest is available even when server-side synthesis isn't
    # (e.g. a GPU-less deployment that relies on in-browser WebGPU synthesis).
    _owned_book(session, book_id, user)
    chapter = _get_chapter(session, book_id, position)
    # Blocks (text + images, in document order) drive the read-along render;
    # chunks index only the text sentences (images carry no audio).
    blocks = segment_blocks(chapter.content)
    paragraphs = [b["sentences"] for b in blocks if b["type"] == "text"]
    _flat, chunks = build_chunks(paragraphs)
    return {
        "chunks": chunks,           # list of chunks; each a list of sentence indices
        "blocks": blocks,           # text paragraphs + images, for read-along
        "voice": voice or DEFAULT_VOICE,
        "speed": speed,
    }


@router.get("/{book_id}/chapters/{position}/audio/{chunk}")
def audio_chunk(book_id: int, position: int, chunk: int,
                voice: Optional[str] = None,
                speed: float = Query(1.0, ge=0.5, le=2.0),
                user: User = Depends(get_current_user),
                session: Session = Depends(get_session)):
    if not tts.available():
        raise HTTPException(status_code=503, detail="TTS is not available")
    _owned_book(session, book_id, user)
    chapter = _get_chapter(session, book_id, position)
    flat, chunks = build_chunks(segment_paragraphs(chapter.content))
    if chunk < 0 or chunk >= len(chunks):
        raise HTTPException(status_code=404, detail="Audio chunk out of range")

    v = tts.valid_voice(voice)
    sp = f"{speed:.2f}"
    cache_dir = settings.audio_dir / str(book_id) / str(position)
    cache_dir.mkdir(parents=True, exist_ok=True)
    mp3_path = cache_dir / f"{v}_{sp}_{chunk}.mp3"
    wav_path = cache_dir / f"{v}_{sp}_{chunk}.wav"
    # Serve whatever's already cached — MP3 preferred; older caches may be WAV.
    if mp3_path.exists():
        return FileResponse(mp3_path, media_type="audio/mpeg")
    if wav_path.exists():
        return FileResponse(wav_path, media_type="audio/wav")

    # Synthesis is CPU/GPU-bound; the sync endpoint runs in the threadpool so it
    # doesn't block the event loop.
    wav = tts.synth_wav(chunk_to_text(flat, chunks[chunk]), v, speed)
    # Compress to MP3 (~6x smaller) so it streams to phones quickly — this is what
    # makes narration start fast over a mobile link. Fall back to WAV if ffmpeg
    # isn't installed.
    mp3 = encode_mp3(wav)
    if mp3 is not None:
        _write_atomic(mp3_path, mp3)
        return FileResponse(mp3_path, media_type="audio/mpeg")
    _write_atomic(wav_path, wav)
    return FileResponse(wav_path, media_type="audio/wav")


@router.get("/{book_id}/cover")
def book_cover(book_id: int, user: User = Depends(get_current_user),
               session: Session = Depends(get_session)):
    book = _owned_book(session, book_id, user)
    if not has_cover(book):
        raise HTTPException(status_code=404, detail="No cover")
    return FileResponse(book.cover_path)


# Illustration filenames are "{16-hex-hash}{ext}" — constrain to that shape so a
# crafted name can't escape the book's image directory (path traversal).
_IMAGE_NAME_RE = re.compile(r"^[A-Za-z0-9]{1,64}\.(jpg|jpeg|png|webp|gif|svg)$")


@router.get("/{book_id}/images/{name}")
def book_image(book_id: int, name: str, user: User = Depends(get_current_user),
               session: Session = Depends(get_session)):
    """Serve an imported EPUB's stored illustration."""
    _owned_book(session, book_id, user)
    if not _IMAGE_NAME_RE.match(name):
        raise HTTPException(status_code=404, detail="No image")
    path = settings.image_dir / str(book_id) / name
    if not path.is_file():
        raise HTTPException(status_code=404, detail="No image")
    return FileResponse(path)


@router.get("/{book_id}/download-all")
def download_all(book_id: int, user: User = Depends(get_current_user),
                 session: Session = Depends(get_session)):
    book = _owned_book(session, book_id, user)
    volumes = session.exec(
        select(Volume).where(Volume.book_id == book_id).order_by(Volume.number)
    ).all()
    if not volumes:
        raise HTTPException(status_code=404, detail="No volumes")

    # Build each volume's EPUB in memory and zip them. Fine single-user.
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        for vol in volumes:
            data = _build_volume_epub(session, book, vol.number)
            if data is not None:
                zf.writestr(epub_filename(book.slug, vol.number), data)
    buffer.seek(0)

    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in book.slug)
    return StreamingResponse(
        buffer,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{safe}.zip"'},
    )


@router.get("/{book_id}/download")
def download_volume(book_id: int, volume: int,
                    user: User = Depends(get_current_user),
                    session: Session = Depends(get_session)):
    book = _owned_book(session, book_id, user)
    vol = session.exec(
        select(Volume).where(Volume.book_id == book_id, Volume.number == volume)
    ).first()
    if vol is None:
        raise HTTPException(status_code=404, detail="Volume not found")
    data = _build_volume_epub(session, book, volume)
    if data is None:
        raise HTTPException(status_code=404, detail="Volume has no chapters")
    filename = epub_filename(book.slug, volume)
    return Response(
        content=data,
        media_type="application/epub+zip",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _build_volume_epub(session: Session, book: Book, volume: int):
    """Build a volume's EPUB in memory from its stored chapters (on demand)."""
    chapters = session.exec(
        select(Chapter)
        .where(Chapter.book_id == book.id, Chapter.volume_number == volume)
        .order_by(Chapter.position)
    ).all()
    if not chapters:
        return None
    return build_epub_bytes(
        book.title or book.slug, book.author, book.language, book.slug,
        chapters, volume, book_id=book.id, image_dir=str(settings.image_dir),
    )


@router.delete("/{book_id}", status_code=204)
def delete_book(book_id: int, user: User = Depends(get_current_user),
                session: Session = Depends(get_session)):
    book = _owned_book(session, book_id, user)
    volumes = session.exec(select(Volume).where(Volume.book_id == book_id)).all()
    for vol in volumes:
        try:
            if os.path.exists(vol.path):
                os.remove(vol.path)
        except OSError:
            pass  # best-effort file cleanup
        session.delete(vol)
    for chapter in session.exec(
        select(Chapter).where(Chapter.book_id == book_id)
    ).all():
        session.delete(chapter)
    if book.cover_path and os.path.exists(book.cover_path):
        try:
            os.remove(book.cover_path)
        except OSError:
            pass
    prog = session.exec(
        select(ReadingProgress).where(ReadingProgress.book_id == book_id)
    ).first()
    if prog is not None:
        # Archive progress by (owner, site, slug) so re-scraping this novel later
        # restores where the reader left off — scoped to the owner so it can't
        # collide with or leak to another user.
        arch = session.exec(
            select(ArchivedProgress).where(
                ArchivedProgress.user_id == book.user_id,
                ArchivedProgress.site == book.site,
                ArchivedProgress.slug == book.slug,
            )
        ).first()
        if arch is None:
            arch = ArchivedProgress(user_id=book.user_id, site=book.site, slug=book.slug)
        arch.source_url = book.source_url
        arch.last_position = prog.last_position
        arch.scroll = prog.scroll
        arch.read_positions = list(prog.read_positions)
        arch.updated_at = datetime.now(timezone.utc)
        session.add(arch)
        session.delete(prog)
    session.exec(
        delete(BookCollectionLink).where(BookCollectionLink.book_id == book_id)
    )
    # Best-effort: drop cached TTS audio + imported illustrations for this book.
    for extra in (settings.audio_dir / str(book_id), settings.image_dir / str(book_id)):
        if extra.exists():
            shutil.rmtree(extra, ignore_errors=True)
    session.delete(book)
    session.commit()
