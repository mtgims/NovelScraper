"""Disk-cache maintenance: keep regenerable caches under a size ceiling.

The scrape HTML cache and the TTS audio cache both grow without bound as the app
is used (one file per fetched page / synthesized chunk) and nothing else evicts
them. On a long-running server that risks filling the disk. These caches are
fully regenerable — HTML is re-fetched, audio re-synthesized on demand — so we
prune them LRU-style (oldest first) back under a configured cap, at startup and
hourly. Real data (EPUB output, covers, the DB) lives elsewhere and is untouched.
"""

from __future__ import annotations

import asyncio
import logging
from pathlib import Path

from .settings import settings

logger = logging.getLogger(__name__)

PRUNE_INTERVAL_SECONDS = 3600
_LOW_WATER = 0.9  # prune down to 90% of the cap so we don't churn on every run


def _remove_empty_dirs(root: Path) -> None:
    """Best-effort removal of empty subdirectories left after eviction."""
    for p in sorted(root.rglob("*"), key=lambda x: len(x.parts), reverse=True):
        if p.is_dir():
            try:
                p.rmdir()  # only succeeds if empty
            except OSError:
                pass


def prune_dir(root: Path, max_bytes: int) -> int:
    """Evict least-recently-modified files under ``root`` until the total is under
    ``_LOW_WATER * max_bytes``. Returns bytes freed. No-op when ``max_bytes <= 0``
    (pruning disabled), the dir is missing, or it's already under the cap."""
    if max_bytes <= 0 or not root.exists():
        return 0

    files: list[tuple[float, int, Path]] = []
    total = 0
    for p in root.rglob("*"):
        if p.is_file():
            try:
                st = p.stat()
            except OSError:
                continue
            files.append((st.st_mtime, st.st_size, p))
            total += st.st_size

    if total <= max_bytes:
        return 0

    target = int(max_bytes * _LOW_WATER)
    files.sort(key=lambda t: t[0])  # oldest first
    freed = 0
    for _mtime, size, path in files:
        if total - freed <= target:
            break
        try:
            path.unlink()
            freed += size
        except OSError:
            continue
    _remove_empty_dirs(root)
    return freed


def prune_caches() -> None:
    """Prune both regenerable caches to their configured ceilings (blocking)."""
    freed_html = prune_dir(settings.cache_dir, settings.max_cache_mb * 1024 * 1024)
    freed_audio = prune_dir(settings.audio_dir, settings.max_audio_mb * 1024 * 1024)
    if freed_html or freed_audio:
        logger.info(
            "cache prune freed %.0f MB html + %.0f MB audio",
            freed_html / 1024 / 1024,
            freed_audio / 1024 / 1024,
        )


async def cache_pruner_loop() -> None:
    """Prune at startup then every hour. Runs the blocking prune off the event
    loop. Cancelled on shutdown."""
    while True:
        try:
            await asyncio.to_thread(prune_caches)
        except Exception:  # noqa: BLE001 — never let maintenance kill the loop
            logger.exception("cache prune failed")
        await asyncio.sleep(PRUNE_INTERVAL_SECONDS)
