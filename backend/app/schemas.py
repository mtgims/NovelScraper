"""Pydantic request/response schemas for the API."""

from __future__ import annotations

from datetime import datetime
from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field


class UserRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    is_admin: bool = False
    disabled: bool = False
    created_at: datetime


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=200)


class RegisterRequest(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=8, max_length=200)
    invite_code: Optional[str] = None


class InviteRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    code: str
    created_by: int
    used_by: Optional[int] = None
    expires_at: datetime
    created_at: datetime


class UserAdminUpdate(BaseModel):
    disabled: Optional[bool] = None
    is_admin: Optional[bool] = None


class ProgressUpdate(BaseModel):
    last_position: Optional[int] = Field(default=None, ge=1)
    scroll: Optional[float] = Field(default=None, ge=0, le=1)
    mark_read: Optional[int] = Field(default=None, ge=1)
    unmark_read: Optional[int] = Field(default=None, ge=1)
    # Bulk marking (range select, "mark above", "mark all others"): a set of
    # chapter positions to add to / remove from the read set in one request.
    mark_positions: Optional[List[int]] = None
    unmark_positions: Optional[List[int]] = None
    mark_all: Optional[bool] = None   # mark every chapter read
    reset: Optional[bool] = None      # clear all progress for the book


class ReadingProgressRead(BaseModel):
    last_position: int
    scroll: float
    read_positions: List[int]
    total_chapters: int
    read_count: int
    chapters_left: int
    percent_read: float
    total_words: int
    words_read: int
    hours_total: float
    hours_left: float


class JobCreate(BaseModel):
    # The user pastes a book (or chapter) URL; the backend resolves which site
    # profile it belongs to and extracts the book id.
    url: str = Field(min_length=1, max_length=2000)
    chapters_per_volume: Optional[int] = Field(default=None, ge=1, le=10000)
    delay: Optional[float] = Field(default=None, ge=0, le=60)
    concurrency: Optional[int] = Field(default=None, ge=1, le=50)


class JobRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    site: str
    book_slug: str
    source_url: Optional[str] = None
    status: str
    phase: str
    chapters_per_volume: int
    total_chapters: int
    fetched_chapters: int
    skipped_chapters: int
    error: Optional[str] = None
    book_id: Optional[int] = None
    created_at: datetime
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None


class VolumeRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    number: int
    title: str
    chapter_count: int
    size_bytes: int


class BookRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    slug: str
    site: str
    title: str
    author: str
    language: str
    has_cover: bool = False
    created_at: datetime
    updated_at: Optional[datetime] = None
    sort_order: int = 0
    rating: Optional[int] = None
    can_update: bool = False   # true when we have a source_url to re-scrape from
    imported: bool = False     # user-imported EPUB(s); can append more EPUBs
    collection_ids: List[int] = []
    volumes: List[VolumeRead] = []


class BookUpdate(BaseModel):
    rating: Optional[int] = Field(default=None, ge=0, le=5)  # 0 clears the rating


class CollectionRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    sort_order: int


class CollectionCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)


class CollectionUpdate(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=100)
    sort_order: Optional[int] = None


class BookCollectionsUpdate(BaseModel):
    collection_ids: List[int] = []


class BookReorder(BaseModel):
    ordered_ids: List[int]


class AppSettings(BaseModel):
    auto_update_hours: int
    allow_open_signup: bool = False


class AppSettingsUpdate(BaseModel):
    # Hours between automatic new-chapter checks; 0 disables the scheduler.
    auto_update_hours: Optional[int] = Field(default=None, ge=0, le=8760)
    # Whether anyone can register without an invite.
    allow_open_signup: Optional[bool] = None


class UpdateDueResult(BaseModel):
    # How many due books were queued for an incremental re-scrape.
    queued: int


class AuthConfig(BaseModel):
    """Public (pre-login) config the register page needs."""
    allow_open_signup: bool


class SiteRead(BaseModel):
    name: str
    base_url: str
    enumeration: str


class BookStat(BaseModel):
    book_id: int
    title: str
    total_chapters: int
    read_count: int
    percent_read: float


class StatsRead(BaseModel):
    total_books: int
    books_started: int
    books_finished: int
    total_chapters: int
    chapters_read: int
    total_words: int
    words_read: int
    hours_read: float
    hours_total: float
    hours_remaining: float
    percent_read: float
    books: List[BookStat]


class ChapterListItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    position: int
    number: str
    title: str
    volume: int   # volume_number this chapter belongs to (for grouping the TOC)


class ChapterRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    position: int
    number: str
    title: str
    content: str
    has_prev: bool
    has_next: bool
