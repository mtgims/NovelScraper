"""Reading statistics, aggregated across the caller's own library."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ..db import get_session
from ..models import Book, ReadingProgress, User
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
