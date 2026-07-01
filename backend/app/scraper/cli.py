"""Command-line entry point for the scraper core.

Run from the ``backend/`` directory:

    python -m app.scraper.cli <book-slug> --site novelfire

This is a thin wrapper for manual use and testing; the web API will call
:func:`scrape_book` directly.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path
from typing import Optional, Sequence

from .config import ScraperConfig
from .errors import ScraperError
from .orchestrator import scrape_book
from .site_profile import load_profiles

# backend/app/scraper/cli.py -> backend/site_profiles
DEFAULT_PROFILE_DIR = Path(__file__).resolve().parents[2] / "site_profiles"


def _progress(phase: str, data: dict) -> None:
    logging.getLogger("scraper.progress").info("%s %s", phase, data)


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="NovelScraper backend CLI")
    p.add_argument("book", help="book slug as it appears in the site URL")
    p.add_argument("--site", default="novelfire", help="site profile name")
    p.add_argument("--profile-dir", default=str(DEFAULT_PROFILE_DIR))
    p.add_argument("--config", help="path to a YAML config file")
    p.add_argument("--chapters-per-volume", type=int)
    p.add_argument("--delay", type=float)
    p.add_argument("--concurrency", type=int)
    p.add_argument("--output-dir")
    p.add_argument("--no-cache", action="store_true", help="disable on-disk caching")
    p.add_argument("--no-robots", action="store_true", help="skip robots.txt checks")
    p.add_argument("-v", "--verbose", action="store_true")
    return p


def _build_config(args: argparse.Namespace) -> ScraperConfig:
    config = ScraperConfig.from_file(args.config) if args.config else ScraperConfig()
    if args.chapters_per_volume is not None:
        config.chapters_per_volume = args.chapters_per_volume
    if args.delay is not None:
        config.delay = args.delay
    if args.concurrency is not None:
        config.max_concurrency = args.concurrency
    if args.output_dir:
        config.output_dir = args.output_dir
    if args.no_cache:
        config.cache_dir = None
    if args.no_robots:
        config.respect_robots = False
    config.validate()
    return config


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    profiles = load_profiles(args.profile_dir)
    profile = profiles.get(args.site)
    if profile is None:
        print(f"Unknown site '{args.site}'. Available: {sorted(profiles)}",
              file=sys.stderr)
        return 2

    try:
        config = _build_config(args)
    except ValueError as e:
        print(f"Invalid configuration: {e}", file=sys.stderr)
        return 2

    try:
        result = asyncio.run(scrape_book(args.book, profile, config, _progress))
    except ScraperError as e:
        print(f"Scrape failed: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Interrupted.", file=sys.stderr)
        return 130

    print(f"\nDone. {result.total_chapters} chapters found, "
          f"{result.skipped_chapters} skipped, {len(result.volumes)} volume(s):")
    for vol in result.volumes:
        print(f"  - {vol.path} ({vol.chapter_count} chapters)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
