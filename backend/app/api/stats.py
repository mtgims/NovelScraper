"""Global reading statistics (single-user, aggregated across all books)."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ..db import get_session
from ..models import Book, Chapter, ReadingProgress
from ..schemas import BookStat, StatsRead
from .books import WORDS_PER_MINUTE, _ensure_word_counts

router = APIRouter()


@router.get("", response_model=StatsRead)
def get_stats(session: Session = Depends(get_session)):
    books = session.exec(select(Book).order_by(Book.created_at.desc())).all()
    progress = {
        p.book_id: p
        for p in session.exec(select(ReadingProgress)).all()
    }

    total_chapters = chapters_read = total_words = words_read = 0
    books_started = books_finished = 0
    per_book: list[BookStat] = []

    for book in books:
        _ensure_word_counts(session, book.id)
        rows = session.exec(
            select(Chapter.position, Chapter.word_count).where(
                Chapter.book_id == book.id
            )
        ).all()
        words = {pos: (wc or 0) for pos, wc in rows}
        tc = len(words)
        prog = progress.get(book.id)
        read = [p for p in (prog.read_positions if prog else []) if p in words]
        rc = len(read)

        total_chapters += tc
        chapters_read += rc
        total_words += sum(words.values())
        words_read += sum(words[p] for p in read)
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
