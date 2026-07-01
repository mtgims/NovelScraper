"""Typed data models shared across the scraper core.

These replace the loose dicts used by the original script and double as the
boundary types the future API/job layer will serialize.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class Chapter:
    number: str
    title: str
    url: str
    content: Optional[str] = None  # cleaned HTML, populated after a content fetch

    @property
    def has_content(self) -> bool:
        return bool(self.content)


@dataclass
class Book:
    slug: str
    title: str = ""
    author: str = "Unknown Author"
    language: str = "en"
    cover_path: Optional[str] = None  # local path to a downloaded cover image

    def display_title(self) -> str:
        return self.title or self.slug.replace("-", " ").title()


@dataclass
class VolumeResult:
    number: int
    title: str
    path: str
    chapter_count: int


@dataclass
class ScrapeResult:
    book: Book
    total_chapters: int
    volumes: List[VolumeResult] = field(default_factory=list)
    skipped_chapters: int = 0
