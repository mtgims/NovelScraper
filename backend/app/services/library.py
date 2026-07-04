"""Book serialization: turn Book rows into API ``BookRead`` payloads (with
volumes, collection memberships and cover presence).

Shared by the books and imports routers so every book-returning endpoint emits
the same shape. ``books_read`` fetches volumes + memberships in bulk to avoid an
N+1 across the library list.
"""

from __future__ import annotations

import os
from collections import defaultdict
from typing import List

from sqlmodel import Session, select

from ..models import Book, BookCollectionLink, Volume
from ..schemas import BookRead, VolumeRead


def has_cover(book: Book) -> bool:
    return bool(book.cover_path) and os.path.exists(book.cover_path)


def _base(book: Book) -> BookRead:
    """The book's own columns, minus volumes/collections (filled by callers)."""
    data = BookRead.model_validate(book)
    data.has_cover = has_cover(book)
    data.can_update = bool(book.source_url)
    return data


def book_read(session: Session, book: Book) -> BookRead:
    """Full ``BookRead`` for a single book (its volumes + collection memberships)."""
    volumes = session.exec(
        select(Volume).where(Volume.book_id == book.id).order_by(Volume.number)
    ).all()
    links = session.exec(
        select(BookCollectionLink.collection_id).where(
            BookCollectionLink.book_id == book.id
        )
    ).all()
    data = _base(book)
    data.collection_ids = sorted(links)
    data.volumes = [VolumeRead.model_validate(v) for v in volumes]
    return data


def books_read(session: Session, books: List[Book]) -> List[BookRead]:
    """``BookRead`` for many books, fetching volumes + memberships in two bulk
    queries instead of two per book."""
    if not books:
        return []
    ids = [b.id for b in books]
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
        data = _base(book)
        data.volumes = by_book.get(book.id, [])
        data.collection_ids = sorted(colls_by_book.get(book.id, []))
        result.append(data)
    return result
