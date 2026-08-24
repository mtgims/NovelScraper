"""Unit tests for the generic (profile-less) extractor: content extraction across
the surveyed TL clusters, next/first-chapter heuristics, and the resolve_book_url
fallback (unknown host -> generic, known host -> its profile)."""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.scraper import build_generic_profile, resolve_book_url
from app.scraper.errors import ContentNotFoundError
from app.scraper.parser import (find_first_chapter_generic, find_next_link_generic,
                                parse_chapter_content_generic, parse_title_generic)
from app.scraper.site_profile import load_profiles

_p = _f = 0


def check(name, cond, detail=""):
    global _p, _f
    if cond:
        _p += 1; print(f"[{name}] PASS {detail}")
    else:
        _f += 1; print(f"[{name}] FAIL {detail}")


BODY = "<p>" + ("A sentence with several words. " * 40) + "</p><p>" + ("More words here. " * 40) + "</p>"

# The surveyed clusters, each wrapping the same body in its characteristic container.
CLUSTERS = {
    "wordpress-entry": f'<div class="entry-content"><h1>Chapter 1</h1>{BODY}</div>',
    "madara-text-left": f'<div class="reading-content"><div class="text-left">{BODY}</div></div>',
    "novelfull-id": f'<div id="chapter-content">{BODY}</div>',
    "chapter-content": f'<div class="chapter-content">{BODY}</div>',
    "cha-content": f'<div class="cha-content">{BODY}</div>',
}


def page(inner, extra=""):
    return f"<html><head><title>Ch1 - Novel</title></head><body><nav><a href='/'>home</a></nav>{inner}{extra}</body></html>"


def main() -> int:
    # 1) content extraction across clusters
    for name, inner in CLUSTERS.items():
        out = parse_chapter_content_generic(page(inner))
        check(f"extract-{name}", out.count("<p>") >= 2 and len(out) > 800, f"len={len(out)}")

    # 2) dangerous markup stripped, nav removed
    dirty = parse_chapter_content_generic(page(f'<div class="entry-content">{BODY}<script>evil()</script></div>'))
    check("sanitize", "evil" not in dirty and "<script" not in dirty)

    # 3) readability fallback when no known selector matches
    ro = parse_chapter_content_generic(page(f'<div id="weird-custom">{BODY}</div>'))
    check("readability", ro.count("<p>") >= 2 and len(ro) > 800, f"len={len(ro)}")

    # 4) too little text -> ContentNotFoundError
    try:
        parse_chapter_content_generic("<html><body><p>tiny</p></body></html>")
        check("empty-raises", False)
    except ContentNotFoundError:
        check("empty-raises", True)

    # 5) next-link heuristics
    check("next-rel", find_next_link_generic(page("<a rel='next' href='/c2'>x</a>"), "https://s.com/c1") == "https://s.com/c2")
    check("next-text", find_next_link_generic(page("<a href='/c2'>Next Chapter</a>"), "https://s.com/c1") == "https://s.com/c2")
    check("next-arrow", find_next_link_generic(page("<a href='/c2'>»</a>"), "https://s.com/c1") == "https://s.com/c2")
    check("next-none", find_next_link_generic(page("<a href='/c0'>Previous</a>"), "https://s.com/c1") is None)

    # 6) first-chapter from a TOC page
    check("first-text", find_first_chapter_generic("<a href='/c1'>Start Reading</a>", "https://s.com") == "https://s.com/c1")
    check("first-href", find_first_chapter_generic("<a href='/book/chapter-1'>go</a>", "https://s.com") == "https://s.com/book/chapter-1")

    # 7) resolve_book_url: unknown -> generic, known -> its profile
    profs = load_profiles("site_profiles")
    gp, slug = resolve_book_url("https://brand-new-tl.com/novel/foo/chapter-1", profs)
    check("resolve-generic", gp.generic and gp.enumeration == "generic" and gp.name == "generic", f"slug={slug}")
    check("resolve-slug-stable", resolve_book_url("https://brand-new-tl.com/novel/foo/chapter-1", profs)[1] == slug)
    kp, _ = resolve_book_url("https://novelbuddy.me/shadow-slave", profs)
    check("resolve-known-wins", not getattr(kp, "generic", False) and kp.name == "novelbuddy")

    # 8) build_generic_profile shape
    bp = build_generic_profile("https://x.com/a/b?c=d")
    check("build-shape", bp.base_url == "https://x.com" and bp.start_url == "https://x.com/a/b?c=d")

    # 9) _incr_url: sequential-URL fallback for JS-router readers (e.g. novtales)
    from app.scraper.enumerators import _incr_url
    check("incr-dash", _incr_url("https://novtales.com/chapter/foo-ability-58") ==
          "https://novtales.com/chapter/foo-ability-59")
    check("incr-html", _incr_url("https://s.com/read/chapter-1.html") == "https://s.com/read/chapter-2.html")
    check("incr-query", _incr_url("https://s.com/c/9?x=1") == "https://s.com/c/10?x=1")
    check("incr-none", _incr_url("https://s.com/novel/foo-bar") is None)  # no trailing number
    # must not bump an id buried mid-path with a long non-numeric tail
    check("incr-no-midpath", _incr_url("https://s.com/novel-123/table-of-contents") is None)

    print(f"\nSUMMARY: {_p}/{_p + _f} passed")
    return 1 if _f else 0


if __name__ == "__main__":
    raise SystemExit(main())
