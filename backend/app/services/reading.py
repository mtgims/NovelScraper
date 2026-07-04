"""Reading-progress domain logic: word counts, progress payloads, reading-time.

Shared by the books and stats routers so the word-count and reading-time rules
have a single source of truth. (Previously the stats router imported private
helpers from the books router, coupling the two.)
"""

from __future__ import annotations

from sqlmodel import Session, select

from ..models import Chapter, ReadingProgress
from ..scraper.parser import count_words
from ..schemas import ReadingProgressRead

# Average adult reading speed, for time-left estimates.
WORDS_PER_MINUTE = 250
_WORDS_PER_HOUR = WORDS_PER_MINUTE * 60


def ensure_word_counts(session: Session, book_id: int) -> None:
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


def chapter_word_map(session: Session, book_id: int) -> dict[int, int]:
    """position -> word_count (0 when not yet counted) for one book's chapters."""
    rows = session.exec(
        select(Chapter.position, Chapter.word_count).where(
            Chapter.book_id == book_id
        )
    ).all()
    return {pos: (wc or 0) for pos, wc in rows}


def progress_payload(session: Session, book_id: int,
                     prog: ReadingProgress) -> ReadingProgressRead:
    """Enrich a book's stored ReadingProgress with derived totals (read count,
    percent, words read, hours left) for the API response."""
    words = chapter_word_map(session, book_id)
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
        hours_total=round(total_words / _WORDS_PER_HOUR, 2),
        hours_left=round(words_left / _WORDS_PER_HOUR, 2),
    )
