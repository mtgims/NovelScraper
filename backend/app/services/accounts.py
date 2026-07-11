"""Account deletion: remove a user and every trace of their data.

Destructive and admin-initiated. Purges the user's whole library (books plus
their volumes/chapters/reading-progress/collection links and the on-disk cover,
illustrations and cached TTS audio), their collections, scrape jobs, archived
progress and login sessions, then the account row. Unlike deleting a single book
(which archives progress for a possible re-scrape), nothing is kept here — the
owner is gone.
"""

from __future__ import annotations

import os
import shutil

from sqlmodel import Session, delete, select

from ..models import (
    ArchivedProgress,
    Book,
    BookCollectionLink,
    Chapter,
    Collection,
    Invite,
    Job,
    ReadingProgress,
    User,
    UserSession,
    Volume,
)
from ..settings import settings


def _purge_book(session: Session, book: Book) -> None:
    """Delete a book and all its rows + on-disk files (no progress archiving)."""
    book_id = book.id
    for vol in session.exec(select(Volume).where(Volume.book_id == book_id)).all():
        session.delete(vol)
    for chapter in session.exec(select(Chapter).where(Chapter.book_id == book_id)).all():
        session.delete(chapter)
    for prog in session.exec(
        select(ReadingProgress).where(ReadingProgress.book_id == book_id)
    ).all():
        session.delete(prog)
    session.exec(delete(BookCollectionLink).where(BookCollectionLink.book_id == book_id))
    if book.cover_path and os.path.exists(book.cover_path):
        try:
            os.remove(book.cover_path)
        except OSError:
            pass
    for d in (settings.audio_dir / str(book_id), settings.image_dir / str(book_id)):
        if d.exists():
            shutil.rmtree(d, ignore_errors=True)
    session.delete(book)


def delete_user(session: Session, user_id: int) -> None:
    """Delete a user and everything they own (see module docstring)."""
    for book in session.exec(select(Book).where(Book.user_id == user_id)).all():
        _purge_book(session, book)
    for coll in session.exec(select(Collection).where(Collection.user_id == user_id)).all():
        session.exec(delete(BookCollectionLink).where(BookCollectionLink.collection_id == coll.id))
        session.delete(coll)
    for job in session.exec(select(Job).where(Job.user_id == user_id)).all():
        session.delete(job)
    for ap in session.exec(select(ArchivedProgress).where(ArchivedProgress.user_id == user_id)).all():
        session.delete(ap)
    for sess in session.exec(select(UserSession).where(UserSession.user_id == user_id)).all():
        session.delete(sess)
    # Invites this user created go with them; invites they *used* are historical
    # (they record a now-consumed code) — keep the row, drop the dangling ref.
    for inv in session.exec(select(Invite).where(Invite.created_by == user_id)).all():
        session.delete(inv)
    for inv in session.exec(select(Invite).where(Invite.used_by == user_id)).all():
        inv.used_by = None
        session.add(inv)
    user = session.get(User, user_id)
    if user is not None:
        session.delete(user)
    session.commit()
