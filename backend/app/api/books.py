"""Library endpoints: list/read scraped books, download volumes, delete."""

from __future__ import annotations

import io
import os
import shutil
import zipfile
from collections import defaultdict
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import FileResponse, StreamingResponse
from sqlmodel import Session, delete, select

from ..db import get_session
from ..models import Book, BookCollectionLink, Chapter, Collection, ReadingProgress, Volume
from ..scraper.parser import count_words
from ..settings import settings
from ..tts import DEFAULT_VOICE, build_chunks, chunk_to_text, segment_paragraphs, tts
from ..schemas import (
    BookCollectionsUpdate,
    BookRead,
    BookReorder,
    ChapterListItem,
    ChapterRead,
    ProgressUpdate,
    ReadingProgressRead,
    VolumeRead,
)

router = APIRouter()


def _get_chapter(session: Session, book_id: int, position: int) -> Chapter:
    chapter = session.exec(
        select(Chapter).where(
            Chapter.book_id == book_id, Chapter.position == position
        )
    ).first()
    if chapter is None:
        raise HTTPException(status_code=404, detail="Chapter not found")
    return chapter

# Average adult reading speed, for time-left estimates.
WORDS_PER_MINUTE = 250


def _ensure_word_counts(session: Session, book_id: int) -> None:
    """Backfill word_count for chapters scraped before it existed (one-time)."""
    missing = session.exec(
        select(Chapter).where(
            Chapter.book_id == book_id, Chapter.word_count.is_(None)
        )
    ).all()
    if missing:
        for ch in missing:
            ch.word_count = count_words(ch.content)
            session.add(ch)
        session.commit()


def _progress_payload(session: Session, book_id: int,
                      prog: ReadingProgress) -> ReadingProgressRead:
    rows = session.exec(
        select(Chapter.position, Chapter.word_count).where(
            Chapter.book_id == book_id
        )
    ).all()
    words = {pos: (wc or 0) for pos, wc in rows}
    total = len(words)
    total_words = sum(words.values())
    read = sorted(p for p in prog.read_positions if p in words)
    read_count = len(read)
    words_read = sum(words[p] for p in read)
    words_left = max(0, total_words - words_read)
    return ReadingProgressRead(
        last_position=prog.last_position,
        scroll=prog.scroll,
        read_positions=read,
        total_chapters=total,
        read_count=read_count,
        chapters_left=max(0, total - read_count),
        percent_read=round(read_count / total * 100, 1) if total else 0.0,
        total_words=total_words,
        words_read=words_read,
        hours_total=round(total_words / (WORDS_PER_MINUTE * 60), 2),
        hours_left=round(words_left / (WORDS_PER_MINUTE * 60), 2),
    )


def _has_cover(book: Book) -> bool:
    return bool(book.cover_path) and os.path.exists(book.cover_path)


def _book_read(session: Session, book: Book) -> BookRead:
    volumes = session.exec(
        select(Volume).where(Volume.book_id == book.id).order_by(Volume.number)
    ).all()
    data = BookRead.model_validate(book)
    data.has_cover = _has_cover(book)
    data.volumes = [VolumeRead.model_validate(v) for v in volumes]
    return data


@router.get("", response_model=List[BookRead])
def list_books(session: Session = Depends(get_session)):
    # Manual order first (drag-to-reorder), newest first as a tiebreaker.
    books = session.exec(
        select(Book).order_by(Book.sort_order, Book.created_at.desc())
    ).all()
    if not books:
        return []
    ids = [b.id for b in books]
    # Fetch volumes + collection memberships in bulk (avoid N+1 per book).
    volumes = session.exec(
        select(Volume).where(Volume.book_id.in_(ids)).order_by(Volume.number)
    ).all()
    by_book: dict[int, list[VolumeRead]] = defaultdict(list)
    for v in volumes:
        by_book[v.book_id].append(VolumeRead.model_validate(v))
    links = session.exec(
        select(BookCollectionLink).where(BookCollectionLink.book_id.in_(ids))
    ).all()
    colls_by_book: dict[int, list[int]] = defaultdict(list)
    for link in links:
        colls_by_book[link.book_id].append(link.collection_id)
    result = []
    for book in books:
        data = BookRead.model_validate(book)
        data.has_cover = _has_cover(book)
        data.volumes = by_book.get(book.id, [])
        data.collection_ids = colls_by_book.get(book.id, [])
        result.append(data)
    return result


@router.put("/{book_id}/collections", response_model=BookRead)
def set_book_collections(book_id: int, body: BookCollectionsUpdate,
                         session: Session = Depends(get_session)):
    """Replace the set of collections a book belongs to (checkbox assignment)."""
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="Book not found")
    # Validate the target collections exist, then rewrite the links.
    valid = set(session.exec(
        select(Collection.id).where(Collection.id.in_(body.collection_ids or [0]))
    ).all())
    session.exec(
        delete(BookCollectionLink).where(BookCollectionLink.book_id == book_id)
    )
    for cid in valid:
        session.add(BookCollectionLink(book_id=book_id, collection_id=cid))
    session.commit()
    data = BookRead.model_validate(book)
    data.has_cover = _has_cover(book)
    data.collection_ids = sorted(valid)
    return data


@router.post("/reorder", status_code=204)
def reorder_books(body: BookReorder, session: Session = Depends(get_session)):
    """Assign a manual library order from a full list of book ids (front to back).
    Ids not present keep their existing sort_order (pushed after the ordered set)."""
    for index, book_id in enumerate(body.ordered_ids):
        book = session.get(Book, book_id)
        if book is not None:
            book.sort_order = index
            session.add(book)
    session.commit()


@router.get("/{book_id}", response_model=BookRead)
def get_book(book_id: int, session: Session = Depends(get_session)):
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="Book not found")
    return _book_read(session, book)


@router.get("/{book_id}/chapters", response_model=List[ChapterListItem])
def list_chapters(book_id: int, session: Session = Depends(get_session)):
    if session.get(Book, book_id) is None:
        raise HTTPException(status_code=404, detail="Book not found")
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
def get_chapter(book_id: int, position: int, session: Session = Depends(get_session)):
    chapter = session.exec(
        select(Chapter).where(
            Chapter.book_id == book_id, Chapter.position == position
        )
    ).first()
    if chapter is None:
        raise HTTPException(status_code=404, detail="Chapter not found")
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
def get_progress(book_id: int, session: Session = Depends(get_session)):
    if session.get(Book, book_id) is None:
        raise HTTPException(status_code=404, detail="Book not found")
    _ensure_word_counts(session, book_id)
    prog = session.exec(
        select(ReadingProgress).where(ReadingProgress.book_id == book_id)
    ).first() or ReadingProgress(book_id=book_id)
    return _progress_payload(session, book_id, prog)


@router.put("/{book_id}/progress", response_model=ReadingProgressRead)
def update_progress(book_id: int, body: ProgressUpdate,
                    session: Session = Depends(get_session)):
    if session.get(Book, book_id) is None:
        raise HTTPException(status_code=404, detail="Book not found")
    _ensure_word_counts(session, book_id)
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
    return _progress_payload(session, book_id, prog)


@router.get("/{book_id}/chapters/{position}/audio/manifest")
def audio_manifest(book_id: int, position: int,
                   voice: Optional[str] = None,
                   speed: float = Query(1.0, ge=0.5, le=2.0),
                   session: Session = Depends(get_session)):
    # Segmentation is pure text processing and independent of the server TTS
    # model, so the manifest is available even when server-side synthesis isn't
    # (e.g. a GPU-less deployment that relies on in-browser WebGPU synthesis).
    chapter = _get_chapter(session, book_id, position)
    paragraphs = segment_paragraphs(chapter.content)
    _flat, chunks = build_chunks(paragraphs)
    return {
        "chunks": chunks,           # list of chunks; each a list of sentence indices
        "paragraphs": paragraphs,   # for read-along rendering/highlighting
        "voice": voice or DEFAULT_VOICE,
        "speed": speed,
    }


@router.get("/{book_id}/chapters/{position}/audio/{chunk}")
def audio_chunk(book_id: int, position: int, chunk: int,
                voice: Optional[str] = None,
                speed: float = Query(1.0, ge=0.5, le=2.0),
                session: Session = Depends(get_session)):
    if not tts.available():
        raise HTTPException(status_code=503, detail="TTS is not available")
    chapter = _get_chapter(session, book_id, position)
    flat, chunks = build_chunks(segment_paragraphs(chapter.content))
    if chunk < 0 or chunk >= len(chunks):
        raise HTTPException(status_code=404, detail="Audio chunk out of range")

    v = tts.valid_voice(voice)
    sp = f"{speed:.2f}"
    cache_dir = settings.audio_dir / str(book_id) / str(position)
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / f"{v}_{sp}_{chunk}.wav"
    if not path.exists():
        # Synthesis is CPU-bound; the sync endpoint runs in the threadpool so it
        # doesn't block the event loop.
        data = tts.synth_wav(chunk_to_text(flat, chunks[chunk]), v, speed)
        path.write_bytes(data)
    return FileResponse(path, media_type="audio/wav")


@router.get("/{book_id}/cover")
def book_cover(book_id: int, session: Session = Depends(get_session)):
    book = session.get(Book, book_id)
    if book is None or not _has_cover(book):
        raise HTTPException(status_code=404, detail="No cover")
    return FileResponse(book.cover_path)


@router.get("/{book_id}/download-all")
def download_all(book_id: int, session: Session = Depends(get_session)):
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="Book not found")
    volumes = session.exec(
        select(Volume).where(Volume.book_id == book_id).order_by(Volume.number)
    ).all()
    available = [v for v in volumes if os.path.exists(v.path)]
    if not available:
        raise HTTPException(status_code=410, detail="No EPUB files available")

    # Zip is built in memory; a book's EPUBs total a few MB (fine single-user).
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        for vol in available:
            zf.write(vol.path, arcname=os.path.basename(vol.path))
    buffer.seek(0)

    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in book.slug)
    return StreamingResponse(
        buffer,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{safe}.zip"'},
    )


@router.get("/{book_id}/download")
def download_volume(book_id: int, volume: int, session: Session = Depends(get_session)):
    vol = session.exec(
        select(Volume).where(Volume.book_id == book_id, Volume.number == volume)
    ).first()
    if vol is None:
        raise HTTPException(status_code=404, detail="Volume not found")
    if not os.path.exists(vol.path):
        raise HTTPException(status_code=410, detail="EPUB file is no longer available")
    return FileResponse(
        vol.path,
        media_type="application/epub+zip",
        filename=os.path.basename(vol.path),
    )


@router.delete("/{book_id}", status_code=204)
def delete_book(book_id: int, session: Session = Depends(get_session)):
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="Book not found")
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
        session.delete(prog)
    session.exec(
        delete(BookCollectionLink).where(BookCollectionLink.book_id == book_id)
    )
    # Best-effort: drop cached TTS audio for this book.
    audio_dir = settings.audio_dir / str(book_id)
    if audio_dir.exists():
        shutil.rmtree(audio_dir, ignore_errors=True)
    session.delete(book)
    session.commit()
