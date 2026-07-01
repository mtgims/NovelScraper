"""Declarative per-site configuration.

A profile describes how to talk to one site: how chapters are enumerated and
where content lives in the DOM. New static sites are added by writing a YAML
profile rather than code. URL templates accept ``{base_url}``, ``{book}`` and
(for paginated lists) ``{page}`` placeholders.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union
from urllib.parse import urlparse

import yaml

from .errors import ProfileError, ScraperError

VALID_STRATEGIES = {"paginated", "next_link"}


class UnsupportedSourceError(ScraperError):
    """The pasted URL's host doesn't match any known site profile."""


def _host(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    return host[4:] if host.startswith("www.") else host


def resolve_book_url(
    url: str, profiles: Dict[str, "SiteProfile"]
) -> Tuple["SiteProfile", str]:
    """Map a pasted book/chapter URL to (profile, book-slug).

    Raises UnsupportedSourceError if no profile matches the host, or ProfileError
    if the matching profile can't extract a book id from the URL.
    """
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise UnsupportedSourceError(f"Not a valid http(s) URL: {url!r}")
    host = _host(url)
    for profile in profiles.values():
        if _host(profile.base_url) != host:
            continue
        if not profile.book_url_regex:
            raise ProfileError(
                f"Site '{profile.name}' does not support URL pasting yet")
        match = re.search(profile.book_url_regex, parsed.path)
        if not match:
            raise ProfileError(
                f"Could not find a book id in the URL for '{profile.name}'")
        return profile, match.group("book")
    raise UnsupportedSourceError(f"Unsupported source: {host or url}")


@dataclass
class SiteProfile:
    name: str
    base_url: str
    enumeration: str                       # "paginated" | "next_link"
    content_selector: str                  # CSS selector for the chapter body

    # paginated strategy
    list_url_template: Optional[str] = None
    list_container_selector: Optional[str] = None
    link_selector: str = "a"
    chapter_no_selector: Optional[str] = None
    chapter_title_selector: Optional[str] = None  # title element within a list item

    # How to get each chapter's URL from the matched list item. Default reads the
    # href attribute; some sites put the URL in onclick/data-* — set link_attr to
    # that attribute and link_url_regex to extract the URL from its value.
    link_attr: str = "href"
    link_url_regex: Optional[str] = None

    # next_link strategy
    first_chapter_url_template: Optional[str] = None
    next_link_selector: Optional[str] = None
    title_selector: Optional[str] = None

    # URL resolution: a regex with a named group `book` that extracts the book
    # id/slug from a pasted book or chapter URL's path (e.g. r"/book/(?P<book>[^/?#]+)").
    book_url_regex: Optional[str] = None

    # Optional per-site metadata selectors (override the generic OpenGraph/meta
    # extraction when a site's og tags are messy or wrong).
    book_title_selector: Optional[str] = None
    book_author_selector: Optional[str] = None
    book_cover_selector: Optional[str] = None

    # content cleaning + safety
    strip_selectors: List[str] = field(default_factory=lambda: ["script", "style"])
    max_pages: int = 10000                 # hard cap on enumeration iterations

    def __post_init__(self) -> None:
        self.validate()

    def validate(self) -> None:
        if not self.base_url:
            raise ProfileError(f"Profile '{self.name}': base_url is required")
        if not self.content_selector:
            raise ProfileError(f"Profile '{self.name}': content_selector is required")
        if self.enumeration not in VALID_STRATEGIES:
            raise ProfileError(
                f"Profile '{self.name}': unknown enumeration '{self.enumeration}'. "
                f"Valid: {sorted(VALID_STRATEGIES)}"
            )
        if self.max_pages < 1:
            raise ProfileError(f"Profile '{self.name}': max_pages must be >= 1")
        if self.enumeration == "paginated":
            missing = [k for k in ("list_url_template", "list_container_selector")
                       if not getattr(self, k)]
            if missing:
                raise ProfileError(
                    f"Profile '{self.name}': paginated strategy requires {missing}")
        if self.enumeration == "next_link":
            missing = [k for k in ("first_chapter_url_template", "next_link_selector")
                       if not getattr(self, k)]
            if missing:
                raise ProfileError(
                    f"Profile '{self.name}': next_link strategy requires {missing}")
        if self.book_url_regex:
            try:
                compiled = re.compile(self.book_url_regex)
            except re.error as e:
                raise ProfileError(
                    f"Profile '{self.name}': invalid book_url_regex: {e}")
            if "book" not in compiled.groupindex:
                raise ProfileError(
                    f"Profile '{self.name}': book_url_regex must contain a "
                    f"named group (?P<book>...)")
        if self.link_url_regex:
            try:
                re.compile(self.link_url_regex)
            except re.error as e:
                raise ProfileError(
                    f"Profile '{self.name}': invalid link_url_regex: {e}")

    @classmethod
    def from_dict(cls, data: Dict) -> "SiteProfile":
        if not isinstance(data, dict):
            raise ProfileError("Profile must be a mapping")
        known = set(cls.__dataclass_fields__)
        unknown = set(data) - known
        if unknown:
            raise ProfileError(f"Unknown profile keys: {sorted(unknown)}")
        return cls(**data)

    @classmethod
    def from_file(cls, path: Union[str, Path]) -> "SiteProfile":
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
        return cls.from_dict(data)


def load_profiles(directory: Union[str, Path]) -> Dict[str, SiteProfile]:
    """Load every ``*.yaml`` profile in a directory, keyed by profile name."""
    directory = Path(directory)
    profiles: Dict[str, SiteProfile] = {}
    for path in sorted(directory.glob("*.yaml")):
        profile = SiteProfile.from_file(path)
        if profile.name in profiles:
            raise ProfileError(f"Duplicate profile name '{profile.name}' in {path}")
        profiles[profile.name] = profile
    return profiles
