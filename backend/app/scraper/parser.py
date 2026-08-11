"""Selector-driven HTML extraction.

All site-specific knowledge lives in the :class:`SiteProfile`; this module is
generic and uses CSS selectors so new sites need no code changes.
"""

from __future__ import annotations

import json
import re
from html import escape
from typing import Any, List, Optional
from urllib.parse import urljoin

from bs4 import BeautifulSoup

from .errors import ContentNotFoundError
from .models import Chapter
from .site_profile import SiteProfile


# Always removed from chapter content, regardless of a profile's strip_selectors,
# so neither the EPUB nor a future web reader renders active/embedded content.
_DANGEROUS_TAGS = (
    "script", "style", "iframe", "object", "embed", "form",
    "link", "meta", "base", "noscript", "svg",
)
# URL attributes whose value must not be a javascript:/data: scheme.
_URL_ATTRS = ("href", "src", "xlink:href")


_TAG_RE = re.compile(r"<[^>]+>")


def count_words(html: str) -> int:
    """Rough word count of chapter HTML (tags stripped). Used for reading-time
    and progress estimates."""
    if not html:
        return 0
    return len(_TAG_RE.sub(" ", html).split())


def _soup(html: str) -> BeautifulSoup:
    return BeautifulSoup(html, "html.parser")


def sanitize(node) -> None:
    """Strip active markup from a parsed content subtree, in place."""
    for tag_name in _DANGEROUS_TAGS:
        for tag in node.select(tag_name):
            tag.decompose()
    for tag in node.find_all(True):
        # Drop inline event handlers (onclick, onerror, ...).
        for attr in [a for a in tag.attrs if a.lower().startswith("on")]:
            del tag[attr]
        # Neutralize javascript:/data: URLs. Normalize the way browsers do when
        # resolving a scheme: drop ALL ASCII control chars and whitespace (they're
        # ignored within a scheme) before matching, case-insensitively. This
        # catches evasions like "java\tscript:", "java\rscript:", "\x00javascript:"
        # and entity-encoded control chars (BeautifulSoup already decoded those).
        # Matters for the EPUB-export path; the in-app reader also runs DOMPurify.
        for attr in _URL_ATTRS:
            value = tag.get(attr)
            if isinstance(value, str):
                normalized = re.sub(r"[\x00-\x20]+", "", value).lower()
                if normalized.startswith(("javascript:", "data:", "vbscript:")):
                    del tag[attr]


def parse_chapter_list(html: str, profile: SiteProfile) -> List[Chapter]:
    """Extract chapter links from one chapter-list page. Empty list = no list."""
    soup = _soup(html)
    container = soup.select_one(profile.list_container_selector)
    if container is None:
        return []

    chapters: List[Chapter] = []
    for item in container.select(profile.link_selector):
        raw = item.get(profile.link_attr)
        if raw and profile.link_url_regex:
            # URL is embedded in the attribute value (e.g. onclick JS).
            match = re.search(profile.link_url_regex, raw)
            if not match:
                continue
            groups = match.groupdict()
            raw = groups["url"] if "url" in groups else match.group(1)
        if not raw:
            continue

        if profile.chapter_title_selector:
            node = item.select_one(profile.chapter_title_selector)
            title = node.get_text(strip=True) if node is not None else ""
        else:
            title = (item.get("title") or item.get_text(strip=True) or "").strip()

        number = ""
        if profile.chapter_no_selector:
            node = item.select_one(profile.chapter_no_selector)
            if node is not None:
                number = node.get_text(strip=True)

        chapters.append(Chapter(
            number=number,
            title=title,
            url=urljoin(profile.base_url, raw),
        ))
    return chapters


def parse_chapter_content(html: str, profile: SiteProfile) -> str:
    """Return the cleaned chapter body HTML, or raise if the selector misses."""
    soup = _soup(html)
    matches = soup.select(profile.content_selector)
    if not matches:
        raise ContentNotFoundError(
            f"content selector '{profile.content_selector}' matched nothing")
    if len(matches) == 1:
        content = matches[0]
    else:
        # Some sites split the body across many sibling blocks (e.g. one <div>
        # per paragraph, keyed by a data-attr) with no clean wrapper — gather the
        # matched blocks into a single container. (Soup is discarded after, so
        # reparenting the nodes is safe.)
        content = soup.new_tag("div")
        for node in matches:
            content.append(node.extract())
    for selector in profile.strip_selectors:
        for node in content.select(selector):
            node.decompose()
    sanitize(content)
    # decode() emits well-formed markup with void elements self-closed, which is
    # closer to the XHTML the EPUB writer expects than the raw source.
    return content.decode()


def dig(data: Any, path: str) -> Any:
    """Traverse a dotted key path into nested dicts. Returns None if any hop is
    missing or the value isn't a dict where a key is expected."""
    cur = data
    for key in path.split("."):
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def parse_json_content(text: str, profile: SiteProfile) -> str:
    """Extract a chapter body from a JSON API response and render it as clean
    paragraph HTML. The body is treated as plain text (newline-separated
    paragraphs) and HTML-escaped, so nothing executable can survive."""
    try:
        data = json.loads(text)
    except ValueError as e:
        raise ContentNotFoundError(f"chapter response was not valid JSON: {e}")
    raw = dig(data, profile.json_content_path)
    if not isinstance(raw, str) or not raw.strip():
        raise ContentNotFoundError(
            f"json_content_path '{profile.json_content_path}' matched no text")
    paragraphs = [p.strip() for p in raw.replace("\r\n", "\n").split("\n")]
    return "".join(f"<p>{escape(p)}</p>" for p in paragraphs if p)


def find_link(html: str, selector: str, base_url: str) -> Optional[str]:
    """Return the absolute href of the first anchor matching `selector`, or None."""
    node = _soup(html).select_one(selector)
    if node is None:
        return None
    href = node.get("href")
    if not href:
        return None
    return urljoin(base_url, href)


def find_next_link(html: str, profile: SiteProfile, current_url: str) -> Optional[str]:
    return find_link(html, profile.next_link_selector, current_url)


def parse_title(html: str, profile: SiteProfile) -> str:
    if not profile.title_selector:
        return ""
    soup = _soup(html)
    node = soup.select_one(profile.title_selector)
    if node is None:
        return ""
    # Drop junk nested in the title (e.g. a "| Novel Name" breadcrumb) via the
    # profile's strip_selectors, so a heading like
    # "Chapter 1: Begins<div class=category>Novel</div>" yields just the chapter.
    for selector in profile.strip_selectors:
        for junk in node.select(selector):
            junk.decompose()
    return node.get_text(strip=True)
