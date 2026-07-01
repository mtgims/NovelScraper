"""Rate limiting, retry backoff, and robots.txt handling.

These are the "polite resilience" primitives: space out requests with jitter,
back off exponentially on transient failures (honoring ``Retry-After``), and
respect a site's robots.txt.
"""

from __future__ import annotations

import asyncio
import random
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Awaitable, Callable, Dict, Optional
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser


class AdaptiveRateLimiter:
    """Spaces request *starts* by an interval that adapts to the site.

    It starts at ``base_interval`` and, when ``adaptive`` is on, speeds up after
    a streak of successes and backs off multiplicatively on rate-limit responses
    (429/503) — converging on the fastest rate the site tolerates without manual
    tuning. Safe for concurrent callers: each reserves the next slot under a
    lock, then sleeps outside it so waits overlap. The adjustment hooks
    (``on_success``/``on_rate_limited``) are called synchronously from the event
    loop thread, so they need no locking.
    """

    def __init__(self, base_interval: float, jitter: float = 0.0,
                 max_interval: float = 8.0, min_interval: float = 0.0,
                 adaptive: bool = True) -> None:
        self._min = max(0.0, min_interval)
        self._max = max(self._min, max_interval)
        self._interval = min(self._max, max(self._min, base_interval))
        self._jitter = max(0.0, jitter)
        self._adaptive = adaptive
        self._lock = asyncio.Lock()
        self._next_allowed = 0.0
        self._success_streak = 0
        self._last_backoff = 0.0
        self._limited = False  # has this site rate-limited us yet?

    async def acquire(self) -> None:
        async with self._lock:
            now = time.monotonic()
            start_at = max(now, self._next_allowed)
            extra = (random.uniform(0.0, self._jitter * self._interval)
                     if self._jitter and self._interval else 0.0)
            self._next_allowed = start_at + self._interval + extra
        wait = start_at - time.monotonic()
        if wait > 0:
            await asyncio.sleep(wait)

    def on_rate_limited(self) -> None:
        """A 429/503 was seen: slow down. Bursts of concurrent 429s are coalesced
        into a single backoff step."""
        if not self._adaptive:
            return
        now = time.monotonic()
        # Coalesce a whole burst of concurrent 429s (plus their retries a couple
        # of seconds later) into a single backoff step, so one burst doesn't
        # ratchet the interval up several times.
        if now - self._last_backoff < max(self._interval * 4, 2.0):
            return
        self._last_backoff = now
        if not self._limited:
            # First hit: jump once to a polite rate (~2 req/s) instead of
            # creeping up and 429-ing repeatedly.
            self._limited = True
            self._interval = min(self._max, max(self._interval * 2, 0.5))
        else:
            # Already throttled and still limited: nudge up gently (no doubling,
            # so a couple of stray 429s don't explode the interval).
            self._interval = min(self._max, self._interval * 1.4)
        self._success_streak = 0

    def on_success(self) -> None:
        """A request succeeded: speed up. On a clean site, drive the interval to
        zero (full concurrency speed). Once a site has rate-limited us, recover
        only very slowly, to avoid oscillating back into 429s."""
        if not self._adaptive:
            return
        self._success_streak += 1
        if not self._limited:
            if self._success_streak >= 4 and self._interval > self._min:
                self._success_streak = 0
                self._interval = max(self._min, self._interval * 0.7)
        elif self._success_streak >= 20 and self._interval > 0.2:
            self._success_streak = 0
            self._interval = max(0.2, self._interval * 0.85)

    @property
    def interval(self) -> float:
        return self._interval


def parse_retry_after(value: Optional[str]) -> Optional[float]:
    """Parse a ``Retry-After`` header (delta-seconds or HTTP-date) to seconds."""
    if not value:
        return None
    value = value.strip()
    if value.isdigit():
        return float(value)
    try:
        dt = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        return None
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return max(0.0, (dt - datetime.now(timezone.utc)).total_seconds())


def compute_backoff(attempt: int, *, base: float, factor: float, maximum: float,
                    retry_after: Optional[float] = None) -> float:
    """Seconds to wait before retry ``attempt`` (1-based).

    Honors a server-provided ``Retry-After`` when present; otherwise uses
    exponential backoff with full jitter to avoid synchronized retries.
    """
    if retry_after is not None and retry_after >= 0:
        return min(retry_after, maximum) if maximum > 0 else retry_after
    raw = base * (factor ** (attempt - 1))
    capped = min(raw, maximum) if maximum > 0 else raw
    return random.uniform(0.0, capped)


class RobotsChecker:
    """Caches robots.txt rules per host. Fails open if robots.txt is unreadable."""

    def __init__(self, user_agent: str,
                 fetch_text: Callable[[str], Awaitable[Optional[str]]]) -> None:
        self._ua = user_agent
        self._fetch_text = fetch_text  # raw fetch that bypasses this checker
        self._parsers: Dict[str, Optional[RobotFileParser]] = {}
        self._lock = asyncio.Lock()

    async def allowed(self, url: str) -> bool:
        parsed = urlparse(url)
        host = f"{parsed.scheme}://{parsed.netloc}"
        async with self._lock:
            if host not in self._parsers:
                self._parsers[host] = await self._load(host)
            parser = self._parsers[host]
        if parser is None:
            return True  # fail open: could not read robots.txt
        return parser.can_fetch(self._ua, url)

    async def _load(self, host: str) -> Optional[RobotFileParser]:
        robots_url = urljoin(host, "/robots.txt")
        try:
            text = await self._fetch_text(robots_url)
        except Exception:
            return None
        if text is None:
            return None
        parser = RobotFileParser()
        parser.parse(text.splitlines())
        return parser
