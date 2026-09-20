"""Reading-progress domain logic: word counts, progress payloads, reading-time.

Shared by the books and stats routers so the word-count and reading-time rules
have a single source of truth. (Previously the stats router imported private
helpers from the books router, coupling the two.)
"""

from __future__ import annotations

from collections import defaultdict
from typing import Iterable, Sequence

from sqlmodel import Session, select

from ..models import Chapter, ReadingProgress
from ..scraper.parser import count_words
from ..schemas import ReadingProgressRead

# Average adult reading speed, for time-left estimates.
WORDS_PER_MINUTE = 250
_WORDS_PER_HOUR = WORDS_PER_MINUTE * 60


def ensure_word_counts(session: Session, book_id: int) -> None:
    """Backfill word_count for chapters scraped before it existed (one-time).

    Only chapters that are actually missing a count are loaded — the filter is
    on the indexed book_id plus a NULL test, so once a book is backfilled this
    is an empty result rather than a scan of its content blobs.
    """
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


def books_needing_word_counts(session: Session,
                              book_ids: Sequence[int]) -> list[int]:
    """Which of ``book_ids`` still have a chapter with no word_count.

    One grouped query instead of one probe per book, so a caller spanning the
    whole library (stats) doesn't pay an N+1 just to discover there is nothing
    to backfill — the common case once a library has been read.
    """
    if not book_ids:
        return []
    rows = session.exec(
        select(Chapter.book_id)
        .where(Chapter.book_id.in_(book_ids), Chapter.word_count.is_(None))
        .group_by(Chapter.book_id)
    ).all()
    return list(rows)


def chapter_word_map(session: Session, book_id: int) -> dict[int, int]:
    """position -> word_count (0 when not yet counted) for one book's chapters."""
    rows = session.exec(
        select(Chapter.position, Chapter.word_count).where(
            Chapter.book_id == book_id
        )
    ).all()
    return {pos: (wc or 0) for pos, wc in rows}


def chapter_word_maps(session: Session,
                      book_ids: Sequence[int]) -> dict[int, dict[int, int]]:
    """``chapter_word_map`` for many books in a single query.

    The library-wide stats endpoint used to call the single-book version once
    per book; on a 9-book fixture that alone was 9 of its 24 statements.
    """
    if not book_ids:
        return {}
    rows = session.exec(
        select(Chapter.book_id, Chapter.position, Chapter.word_count)
        .where(Chapter.book_id.in_(book_ids))
    ).all()
    maps: dict[int, dict[int, int]] = defaultdict(dict)
    for book_id, position, word_count in rows:
        maps[book_id][position] = word_count or 0
    return maps


def progress_totals(words: dict[int, int], read_positions: Iterable[int]):
    """``(total, total_words, read, read_count, words_read)`` for one book.

    Pure: takes an already-fetched word map so both the single-book payload and
    the library-wide aggregate can share the arithmetic without re-querying.
    """
    total_words = sum(words.values())
    read = sorted(p for p in read_positions if p in words)
    words_read = sum(words[p] for p in read)
    return len(words), total_words, read, len(read), words_read


def progress_payload(session: Session, book_id: int,
                     prog: ReadingProgress) -> ReadingProgressRead:
    """Enrich a book's stored ReadingProgress with derived totals (read count,
    percent, words read, hours left) for the API response."""
    words = chapter_word_map(session, book_id)
    total, total_words, read, read_count, words_read = progress_totals(
        words, prog.read_positions
    )
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
