"""Reading statistics, aggregated across the caller's own library."""

from __future__ import annotations

import csv
import io

from fastapi import APIRouter, Depends, Query, Response
from sqlmodel import Session, select

from ..db import get_session
from ..models import Book, Chapter, ReadingProgress, User
from ..schemas import BookStat, StatsRead
from ..services.reading import (
    WORDS_PER_MINUTE,
    books_needing_word_counts,
    chapter_word_maps,
    ensure_word_counts,
    progress_totals,
)
from .deps import get_current_user

router = APIRouter()


@router.get("", response_model=StatsRead)
def get_stats(user: User = Depends(get_current_user),
              session: Session = Depends(get_session)):
    books = session.exec(
        select(Book).where(Book.user_id == user.id).order_by(Book.created_at.desc())
    ).all()
    book_ids = [b.id for b in books]
    progress = {
        p.book_id: p
        for p in session.exec(
            select(ReadingProgress).where(ReadingProgress.book_id.in_(book_ids or [0]))
        ).all()
    }

    total_chapters = chapters_read = total_words = words_read = 0
    books_started = books_finished = 0
    per_book: list[BookStat] = []

    # Backfill only the books that actually need it (one grouped query finds
    # them), then fetch every book's word map in one go. This used to be two
    # queries per book — 24 statements for a 9-book library, and growing.
    for stale_id in books_needing_word_counts(session, book_ids):
        ensure_word_counts(session, stale_id)
    word_maps = chapter_word_maps(session, book_ids)

    for book in books:
        words = word_maps.get(book.id, {})
        prog = progress.get(book.id)
        tc, tw, _read, rc, wr = progress_totals(
            words, prog.read_positions if prog else ()
        )

        total_chapters += tc
        chapters_read += rc
        total_words += tw
        words_read += wr
        if rc > 0 or (prog and prog.last_position > 1):
            books_started += 1
        if tc > 0 and rc >= tc:
            books_finished += 1

        per_book.append(BookStat(
            book_id=book.id,
            title=book.title,
            total_chapters=tc,
            read_count=rc,
            percent_read=round(rc / tc * 100, 1) if tc else 0.0,
        ))

    per_minute = WORDS_PER_MINUTE * 60
    return StatsRead(
        total_books=len(books),
        books_started=books_started,
        books_finished=books_finished,
        total_chapters=total_chapters,
        chapters_read=chapters_read,
        total_words=total_words,
        words_read=words_read,
        hours_read=round(words_read / per_minute, 1),
        hours_total=round(total_words / per_minute, 1),
        hours_remaining=round(max(0, total_words - words_read) / per_minute, 1),
        percent_read=round(chapters_read / total_chapters * 100, 1)
        if total_chapters else 0.0,
        books=per_book,
    )


_EXPORT_COLUMNS = [
    ("title", "Title"),
    ("author", "Author"),
    ("site", "Site"),
    ("current_position", "Current chapter #"),
    ("current_chapter", "Current chapter"),
    ("chapters_read", "Chapters read"),
    ("total_chapters", "Total chapters"),
    ("percent_read", "Percent read"),
    ("updated_at", "Updated"),
]


@router.get("/export")
def export_progress(format: str = Query("json"),
                    user: User = Depends(get_current_user),
                    session: Session = Depends(get_session)):
    """Per-novel reading progress for the caller: name/author + where they are
    (current chapter, read/total, %). ``format=csv`` returns a downloadable file;
    default is JSON."""
    books = session.exec(
        select(Book).where(Book.user_id == user.id).order_by(Book.created_at.desc())
    ).all()
    book_ids = [b.id for b in books]
    progress = {
        p.book_id: p
        for p in session.exec(
            select(ReadingProgress).where(ReadingProgress.book_id.in_(book_ids or [0]))
        ).all()
    }

    for stale_id in books_needing_word_counts(session, book_ids):
        ensure_word_counts(session, stale_id)
    word_maps = chapter_word_maps(session, book_ids)

    rows: list[dict] = []
    for book in books:
        words = word_maps.get(book.id, {})
        tc = len(words)
        prog = progress.get(book.id)
        rc = len([p for p in (prog.read_positions if prog else []) if p in words])
        pos = prog.last_position if prog else 1
        cur = session.exec(
            select(Chapter.number, Chapter.title)
            .where(Chapter.book_id == book.id, Chapter.position == pos)
        ).first()
        cur_title = ""
        if cur is not None:
            num, title = cur
            cur_title = title or (f"Chapter {num}" if num else f"Chapter {pos}")
        rows.append({
            "title": book.title,
            "author": book.author,
            "site": book.site,
            "current_position": pos,
            "current_chapter": cur_title,
            "chapters_read": rc,
            "total_chapters": tc,
            "percent_read": round(rc / tc * 100, 1) if tc else 0.0,
            "updated_at": (book.updated_at or book.created_at).isoformat(),
        })

    if format.lower() == "csv":
        buf = io.StringIO()
        writer = csv.writer(buf)
        writer.writerow([label for _, label in _EXPORT_COLUMNS])
        for r in rows:
            writer.writerow([r[key] for key, _ in _EXPORT_COLUMNS])
        return Response(
            content=buf.getvalue(),
            media_type="text/csv",
            headers={"Content-Disposition": "attachment; filename=novelscraper-progress.csv"},
        )
    return rows
