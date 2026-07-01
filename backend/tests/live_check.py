"""Live profile check — hits the real site. Run manually to validate a profile:

    cd backend && .venv/bin/python tests/live_check.py [profile] [book-url]

Defaults to lightnovelworld. Makes a handful of real requests (listing page,
book page, one chapter) — it does NOT scrape the whole novel.
"""

import asyncio, sys, pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from app.scraper import ScraperConfig, load_profiles, resolve_book_url
from app.scraper.fetcher import AsyncFetcher
from app.scraper.parser import parse_chapter_list, parse_chapter_content
from app.scraper.metadata import extract_metadata

PROFILE_DIR = pathlib.Path(__file__).resolve().parents[1] / "site_profiles"
name = sys.argv[1] if len(sys.argv) > 1 else "lightnovelworld"
url = sys.argv[2] if len(sys.argv) > 2 else "https://lightnovelworld.org/novel/cultivation-nerd/"


async def main():
    profiles = load_profiles(PROFILE_DIR)
    profile, slug = resolve_book_url(url, profiles)
    print(f"[resolve] profile={profile.name} slug={slug}")

    cfg = ScraperConfig(delay=0.3, max_concurrency=3, cache_dir=None)
    async with AsyncFetcher(cfg) as f:
        # metadata
        html = await f.get_text(url, use_cache=False)
        meta = extract_metadata(
            html, url,
            title_selector=profile.book_title_selector,
            author_selector=profile.book_author_selector,
            cover_selector=profile.book_cover_selector,
        )
        print(f"[metadata] title={meta.title!r} author={meta.author!r}")
        print(f"[metadata] cover={meta.cover_url}")

        # enumerate page 1 only
        list_url = profile.list_url_template.format(
            base_url=profile.base_url.rstrip('/'), book=slug, page=1)
        list_html = await f.get_text(list_url, use_cache=False)
        chapters = parse_chapter_list(list_html, profile)
        print(f"[enumerate] page 1: {len(chapters)} chapters")
        for c in chapters[:3]:
            print(f"    #{c.number or '-'} {c.title!r} -> {c.url}")

        # fetch first chapter content
        if chapters:
            ch_html = await f.get_text(chapters[0].url)
            content = parse_chapter_content(ch_html, profile)
            words = len(content.split())
            print(f"[content] chapter 1: {len(content)} chars, ~{words} words")
            ok = (len(chapters) > 0 and meta.title and words > 100
                  and chapters[0].url.startswith("http"))
            print("\nRESULT:", "PASS" if ok else "FAIL")
            sys.exit(0 if ok else 1)
        print("\nRESULT: FAIL (no chapters)")
        sys.exit(1)


asyncio.run(main())
