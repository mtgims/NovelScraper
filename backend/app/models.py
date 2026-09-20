"""Database models (SQLModel tables).

Note: this is distinct from ``app.scraper.models`` (the in-memory dataclasses).
These are the persisted entities backing the API.
"""

from __future__ import annotations

import gzip
from datetime import datetime, timezone
from enum import Enum
from typing import List, Optional

from sqlalchemy import JSON, Column, Index, LargeBinary
from sqlalchemy.types import TypeDecorator
from sqlmodel import Field, SQLModel


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class CompressedText(TypeDecorator):
    """A text column stored gzip-compressed as a BLOB — transparent to Python
    code (still a str). Chapter HTML compresses ~3x. Reads tolerate legacy
    uncompressed (TEXT) rows, so the schema migrates in place."""

    impl = LargeBinary
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        return gzip.compress(value.encode("utf-8"), 6)

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        if isinstance(value, str):
            return value  # legacy uncompressed row
        data = bytes(value)
        if data[:2] == b"\x1f\x8b":  # gzip magic
            return gzip.decompress(data).decode("utf-8")
        return data.decode("utf-8")


class JobStatus(str, Enum):
    queued = "queued"
    running = "running"
    completed = "completed"
    failed = "failed"
    cancelled = "cancelled"


class Job(SQLModel, table=True):
    id: str = Field(primary_key=True)
    site: str                     # resolved site profile name
    book_slug: str                # resolved book id/slug
    source_url: Optional[str] = None  # the URL the user pasted
    status: str = Field(default=JobStatus.queued.value, index=True)
    phase: str = "queued"

    # run parameters (overrides; None = use config defaults)
    chapters_per_volume: int = 100
    delay: Optional[float] = None
    concurrency: Optional[int] = None
    incremental: bool = False     # update mode: fetch only new chapters, append

    # progress / outcome
    total_chapters: int = 0
    fetched_chapters: int = 0
    skipped_chapters: int = 0
    error: Optional[str] = None
    book_id: Optional[int] = Field(default=None, foreign_key="book.id")
    # Owner. Nullable so the additive migration can add it to old DBs; a startup
    # backfill then assigns pre-auth rows to the bootstrap admin (see db.py).
    user_id: Optional[int] = Field(default=None, foreign_key="user.id", index=True)

    created_at: datetime = Field(default_factory=utcnow)
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None


class Book(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    slug: str = Field(index=True)
    site: str
    title: str = ""
    author: str = "Unknown Author"
    language: str = "en"
    cover_path: Optional[str] = None
    created_at: datetime = Field(default_factory=utcnow)
    # Manual library order (drag-to-reorder). Ties fall back to created_at desc.
    sort_order: int = Field(default=0)
    rating: Optional[int] = Field(default=None)       # 1-5 stars; None = unrated
    source_url: Optional[str] = Field(default=None)   # original URL, for re-scrape/update
    updated_at: Optional[datetime] = Field(default=None)  # last successful scrape/update
    imported: bool = Field(default=False)  # user-imported EPUB(s), not scraped
    # Owner (nullable for additive migration; backfilled to admin — see db.py).
    user_id: Optional[int] = Field(default=None, foreign_key="user.id", index=True)


class Collection(SQLModel, table=True):
    """A user-defined library group (Mihon-style category). A book can belong to
    many collections via BookCollectionLink."""
    id: Optional[int] = Field(default=None, primary_key=True)
    name: str
    sort_order: int = Field(default=0)   # tab order
    created_at: datetime = Field(default_factory=utcnow)
    # Owner (nullable for additive migration; backfilled to admin — see db.py).
    user_id: Optional[int] = Field(default=None, foreign_key="user.id", index=True)


class BookCollectionLink(SQLModel, table=True):
    book_id: int = Field(foreign_key="book.id", primary_key=True)
    collection_id: int = Field(foreign_key="collection.id", primary_key=True)


class Setting(SQLModel, table=True):
    """Simple persisted key/value app settings (editable from the UI), as opposed
    to the env-only Settings in app.settings."""
    key: str = Field(primary_key=True)
    value: str


class ArchivedProgress(SQLModel, table=True):
    """Reading progress retained after a book is deleted, keyed by (user, site,
    slug) so it can be auto-restored if the same novel is scraped again later.
    Book ids change across delete+re-scrape, but site+slug (from the URL) don't.

    Now scoped per user: the identity is (user_id, site, slug) — enforced in the
    service layer (upsert), so one user's archived progress never overwrites or
    leaks to another. Uses a surrogate id PK (the pre-auth table's (site, slug)
    PK is rebuilt into this shape on migration; see db.py)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: Optional[int] = Field(default=None, foreign_key="user.id", index=True)
    site: str = Field(index=True)
    slug: str = Field(index=True)
    source_url: Optional[str] = None
    last_position: int = 1
    scroll: float = 0.0
    read_positions: List[int] = Field(default_factory=list, sa_column=Column(JSON))
    updated_at: datetime = Field(default_factory=utcnow)


class Volume(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", index=True)
    number: int
    title: str
    path: str
    chapter_count: int = 0
    size_bytes: int = 0


class Chapter(SQLModel, table=True):
    # Every read path looks a chapter up by (book, position): the reader fetch,
    # its has_next probe, and the volume export. With only the book_id index
    # SQLite had to walk every row of the book (2334 for the largest in the
    # fixture library) checking position; this makes it one seek.
    __table_args__ = (Index("ix_chapter_book_id_position", "book_id", "position"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", index=True)
    position: int                 # 1-based global reading order within the book
    volume_number: int
    number: str = ""              # the site's chapter label (may be non-numeric)
    title: str = ""
    # Sanitized chapter HTML, stored gzip-compressed (transparent — still a str).
    content: str = Field(default="", sa_column=Column(CompressedText()))
    word_count: Optional[int] = None  # None until computed/backfilled


class ReadingProgress(SQLModel, table=True):
    """Per-book reading state. Single-user for now; a user_id can be added later
    (auth-ready). One row per book."""

    id: Optional[int] = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", unique=True, index=True)
    last_position: int = 1        # resume point (chapter position)
    scroll: float = 0.0           # 0..1 within the last-read chapter
    read_positions: List[int] = Field(default_factory=list, sa_column=Column(JSON))
    updated_at: datetime = Field(default_factory=utcnow)
    # Ownership is inherited through the parent Book (book_id) — a ReadingProgress
    # row is only ever reachable via a Book the user owns. No user_id column here.


class User(SQLModel, table=True):
    """An application account. The first user is created as an admin at startup
    from env (see db.py bootstrap); further accounts come via invite/signup."""
    id: Optional[int] = Field(default=None, primary_key=True)
    username: str = Field(index=True, unique=True)
    password_hash: str            # scrypt hash (see app.security)
    is_admin: bool = Field(default=False)
    disabled: bool = Field(default=False)  # soft-lock: keeps data, blocks login
    created_at: datetime = Field(default_factory=utcnow)


class UserSession(SQLModel, table=True):
    """A server-side login session backing the httpOnly cookie. The cookie holds
    an opaque random token; only its SHA-256 hash is stored here (so a DB leak
    can't be replayed as a cookie). Deleting the row = immediate logout."""
    token_hash: str = Field(primary_key=True)   # sha256(cookie token)
    user_id: int = Field(foreign_key="user.id", index=True)
    created_at: datetime = Field(default_factory=utcnow)
    expires_at: datetime = Field(index=True)    # absolute expiry; slid on use


class Invite(SQLModel, table=True):
    """A single-use, expiring registration token. Stored in plaintext so an admin
    can re-copy a pending invite from the UI; it only grants account creation,
    which an admin can disable. Consumed by setting used_by on register."""
    code: str = Field(primary_key=True)
    created_by: int = Field(foreign_key="user.id")
    used_by: Optional[int] = Field(default=None, foreign_key="user.id")
    expires_at: datetime
    created_at: datetime = Field(default_factory=utcnow)
