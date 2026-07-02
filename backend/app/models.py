"""Database models (SQLModel tables).

Note: this is distinct from ``app.scraper.models`` (the in-memory dataclasses).
These are the persisted entities backing the API.
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import List, Optional

from sqlalchemy import JSON, Column
from sqlmodel import Field, SQLModel


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


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


class Collection(SQLModel, table=True):
    """A user-defined library group (Mihon-style category). A book can belong to
    many collections via BookCollectionLink."""
    id: Optional[int] = Field(default=None, primary_key=True)
    name: str
    sort_order: int = Field(default=0)   # tab order
    created_at: datetime = Field(default_factory=utcnow)


class BookCollectionLink(SQLModel, table=True):
    book_id: int = Field(foreign_key="book.id", primary_key=True)
    collection_id: int = Field(foreign_key="collection.id", primary_key=True)


class Setting(SQLModel, table=True):
    """Simple persisted key/value app settings (editable from the UI), as opposed
    to the env-only Settings in app.settings."""
    key: str = Field(primary_key=True)
    value: str


class Volume(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", index=True)
    number: int
    title: str
    path: str
    chapter_count: int = 0
    size_bytes: int = 0


class Chapter(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", index=True)
    position: int                 # 1-based global reading order within the book
    volume_number: int
    number: str = ""              # the site's chapter label (may be non-numeric)
    title: str = ""
    content: str = ""            # sanitized chapter HTML
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
