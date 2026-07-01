"""Generic book-metadata extraction from a book page.

Uses OpenGraph / standard ``<meta>`` tags (and a few common fallbacks) so it
works across most sites without per-site configuration: title, author, and a
cover image URL.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional
from urllib.parse import urljoin

from bs4 import BeautifulSoup


@dataclass
class BookMetadata:
    title: str = ""
    author: str = ""
    cover_url: Optional[str] = None


def _meta(soup: BeautifulSoup, *, prop: Optional[str] = None,
          name: Optional[str] = None) -> Optional[str]:
    if prop:
        tag = soup.find("meta", attrs={"property": prop})
        if tag and tag.get("content"):
            return tag["content"].strip()
    if name:
        tag = soup.find("meta", attrs={"name": name})
        if tag and tag.get("content"):
            return tag["content"].strip()
    return None


def _clean_author(text: Optional[str]) -> str:
    if not text:
        return ""
    # Strip a leading label like "Author:" / "Authors -".
    return re.sub(r"^\s*authors?\s*[:：\-]\s*", "", text.strip(), flags=re.I).strip()


def _text_of(soup: BeautifulSoup, selector: Optional[str]) -> Optional[str]:
    if not selector:
        return None
    node = soup.select_one(selector)
    return node.get_text(strip=True) if node is not None else None


def extract_metadata(
    html: str,
    base_url: str,
    *,
    title_selector: Optional[str] = None,
    author_selector: Optional[str] = None,
    cover_selector: Optional[str] = None,
) -> BookMetadata:
    """Extract title/author/cover. Per-site selectors take precedence over the
    generic OpenGraph/meta extraction (used when a site's og tags are messy)."""
    soup = BeautifulSoup(html, "html.parser")

    title = (
        _text_of(soup, title_selector)
        or _meta(soup, prop="og:title")
        or _meta(soup, name="twitter:title")
        or (soup.title.get_text(strip=True) if soup.title else "")
    )

    cover = None
    if cover_selector:
        node = soup.select_one(cover_selector)
        if node is not None:
            cover = node.get("src") or node.get("data-src") or node.get("content")
    cover = cover or (
        _meta(soup, prop="og:image")
        or _meta(soup, name="twitter:image")
        or _meta(soup, prop="og:image:url")
    )
    if cover:
        cover = urljoin(base_url, cover)

    author = _text_of(soup, author_selector)
    if not author:
        author = (
            _meta(soup, name="author")
            or _meta(soup, prop="book:author")
            or _meta(soup, prop="books:author")
        )
    if not author:
        node = soup.select_one('[itemprop="author"], a[rel="author"], .author-name')
        if node:
            author = node.get_text(strip=True)

    return BookMetadata(
        title=(title or "").strip(),
        author=_clean_author(author),
        cover_url=cover,
    )
