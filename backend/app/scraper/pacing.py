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

# Upper bound on a server-requested cooldown, so a hostile or misconfigured
# Retry-After (e.g. 86400) can't stall a scrape indefinitely. Long enough to
# honor real multi-minute fixed windows (e.g. 300s).
MAX_COOLDOWN_SECONDS = 600.0


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
                 adaptive: bool = True, budget_reserve: int = 0) -> None:
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
        # A hard cooldown deadline (monotonic time). While in the future, EVERY
        # caller waits for it before its request — so a fixed-window limiter that
        # only resets once it stops receiving requests actually gets the silence
        # it needs. Set from a server Retry-After / RateLimit-Reset.
        self._cooldown_until = 0.0
        # Proactive pacing: when a site advertises its budget via RateLimit-*
        # headers, spread the remaining budget over the remaining window instead
        # of bursting into the wall. 0 = no advertised budget (stays dormant).
        self._budget_reserve = max(0, budget_reserve)
        self._proactive_interval = 0.0

    async def acquire(self) -> None:
        async with self._lock:
            now = time.monotonic()
            # Space by whichever is slower: the adaptive interval or the
            # budget-derived proactive interval.
            interval = max(self._interval, self._proactive_interval)
            # Gate on the per-request interval AND any active hard cooldown.
            start_at = max(now, self._next_allowed, self._cooldown_until)
            extra = (random.uniform(0.0, self._jitter * interval)
                     if self._jitter and interval else 0.0)
            self._next_allowed = start_at + interval + extra
        wait = start_at - time.monotonic()
        if wait > 0:
            await asyncio.sleep(wait)

    def on_budget(self, remaining: Optional[float], reset: Optional[float]) -> None:
        """Proactively pace from a server's advertised rate-limit budget
        (IETF RateLimit-Remaining / RateLimit-Reset, in seconds). Keeps a small
        reserve for in-flight requests whose consumption isn't reflected yet;
        once the reserve is reached, stop until the window resets (reusing the
        cooldown gate) rather than racing the last few requests into a 429."""
        if remaining is None or reset is None or reset < 0:
            return  # site doesn't advertise a budget -> stay dormant
        usable = remaining - self._budget_reserve
        if usable <= 0:
            # Out of headroom: wait for the window to refill.
            cd = min(reset, MAX_COOLDOWN_SECONDS)
            self._cooldown_until = max(self._cooldown_until, time.monotonic() + cd)
            self._proactive_interval = 0.0
        else:
            # Spread the usable budget evenly over the time left in the window.
            self._proactive_interval = min(reset / usable, self._max)

    def on_rate_limited(self, retry_after: Optional[float] = None) -> None:
        """A 429/503 was seen: open a hard cooldown for the server-specified
        window (if any) and slow the steady-state rate. Bursts of concurrent
        429s are coalesced into a single interval backoff step, but each one
        still extends the cooldown to the latest deadline."""
        now = time.monotonic()
        # Honor an explicit cooldown regardless of adaptive pacing — it's a
        # direct server instruction, and it's what lets a fixed-window limiter
        # recover instead of being kept pinned by continued requests.
        if retry_after and retry_after > 0:
            capped = min(retry_after, MAX_COOLDOWN_SECONDS)
            self._cooldown_until = max(self._cooldown_until, now + capped)
        if not self._adaptive:
            return
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
