"""HTTP fetcher with a concurrency cap, jittered rate limiting, retry-with-
backoff, robots.txt enforcement, an SSRF guard, and an optional on-disk cache.

Transport is curl_cffi with browser TLS impersonation, so Cloudflare-protected
sites (which fingerprint and block plain Python HTTP clients like aiohttp) can
still be scraped. All the safety layers wrap that transport.
"""

from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import logging
import socket
import urllib.request
from pathlib import Path
from typing import Optional
from urllib.parse import urljoin, urlparse

from curl_cffi import CurlError
from curl_cffi.requests import AsyncSession
from curl_cffi.requests.errors import RequestsError

from .config import ScraperConfig
from .errors import FetchError, RetryableFetchError
from .pacing import (
    AdaptiveRateLimiter,
    RobotsChecker,
    compute_backoff,
    parse_retry_after,
)

logger = logging.getLogger(__name__)

# Status codes worth retrying. Everything else >= 400 is treated as fatal.
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}
# Codes that signal we're going too fast, feeding the adaptive limiter.
RATE_LIMIT_STATUS = {429, 503}
REDIRECT_STATUS = {301, 302, 303, 307, 308}
MAX_REDIRECTS = 10
# curl_cffi transport errors (timeout, connection reset, TLS) -> retryable.
NETWORK_ERRORS = (RequestsError, CurlError)


class AsyncFetcher:
    """Use as an async context manager so the HTTP session is always closed."""

    def __init__(self, config: ScraperConfig) -> None:
        self.config = config
        self._session: Optional[AsyncSession] = None
        self._semaphore = asyncio.Semaphore(config.max_concurrency)
        self._limiter = AdaptiveRateLimiter(
            config.delay, config.jitter,
            max_interval=config.max_interval, adaptive=config.adaptive_pacing)
        self._robots: Optional[RobotsChecker] = None
        self._cache_dir = Path(config.cache_dir) if config.cache_dir else None

    async def __aenter__(self) -> "AsyncFetcher":
        self._session = AsyncSession(
            impersonate=self.config.impersonate,
            timeout=self.config.request_timeout,
            headers={"Accept-Language": "en-US,en;q=0.9"},
        )
        if self.config.respect_robots:
            self._robots = RobotsChecker(self.config.user_agent, self._raw_text)
        if self._cache_dir:
            self._cache_dir.mkdir(parents=True, exist_ok=True)
        return self

    async def __aexit__(self, *exc) -> None:
        if self._session is not None:
            await self._session.close()
            self._session = None

    # --- internals -------------------------------------------------------

    async def _validate_url(self, url: str) -> None:
        """Reject non-HTTP(S) URLs and, unless explicitly allowed, any URL that
        resolves to a private/loopback/link-local/reserved address (SSRF guard).

        Pre-connection check; it doesn't fully close the DNS-rebinding window but
        blocks the common SSRF vectors (internal hostnames, metadata IPs).
        """
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https"):
            raise FetchError(f"Unsupported URL scheme '{parsed.scheme}'", url=url)
        host = parsed.hostname
        if not host:
            raise FetchError(f"URL has no host: {url}", url=url)
        if self.config.allow_private_hosts:
            return
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        try:
            infos = await asyncio.get_running_loop().getaddrinfo(
                host, port, proto=socket.IPPROTO_TCP)
        except socket.gaierror as e:
            raise FetchError(f"DNS resolution failed for '{host}': {e}", url=url)
        for info in infos:
            ip = ipaddress.ip_address(info[4][0])
            if (ip.is_private or ip.is_loopback or ip.is_link_local
                    or ip.is_reserved or ip.is_multicast or ip.is_unspecified):
                raise FetchError(
                    f"Refusing to fetch private/internal address {ip} ({host})",
                    url=url)

    def _cap_and_decode(self, resp, binary: bool):
        """Enforce the size cap on an already-downloaded body; decode unless binary.

        Note: curl_cffi buffers the full body, so the cap rejects *after* download
        rather than aborting mid-stream (acceptable for a scraper hitting known
        sites; the cap still bounds what's kept in memory / parsed)."""
        body = resp.content
        limit = self.config.max_response_bytes
        if limit and limit > 0 and len(body) > limit:
            raise FetchError(f"Response exceeded {limit} bytes",
                             url=str(resp.url), status=resp.status_code)
        if binary:
            return body
        encoding = resp.encoding or "utf-8"
        try:
            return body.decode(encoding, errors="replace")
        except LookupError:
            return body.decode("utf-8", errors="replace")

    async def _raw_text(self, url: str) -> Optional[str]:
        """Fetch without robots/cache. Used to retrieve robots.txt itself."""
        assert self._session is not None
        try:
            await self._validate_url(url)
        except FetchError:
            return None
        async with self._semaphore:
            await self._limiter.acquire()
            try:
                resp = await self._session.get(url, allow_redirects=False)
                if resp.status_code != 200:
                    return None  # missing/redirected robots.txt -> fail open
                return self._cap_and_decode(resp, False)
            except (FetchError, *NETWORK_ERRORS):
                return None

    def _cache_path(self, url: str) -> Optional[Path]:
        if not self._cache_dir:
            return None
        digest = hashlib.sha256(url.encode("utf-8")).hexdigest()
        return self._cache_dir / f"{digest}.html"

    async def _request(self, url: str, binary: bool = False):
        """One logical fetch, following redirects manually so every hop is
        SSRF-validated. Raises RetryableFetchError (transient) or FetchError
        (fatal: bad status, oversize, disallowed target, too many redirects).
        """
        assert self._session is not None
        current = url
        for _ in range(MAX_REDIRECTS + 1):
            resp = await self._session.get(current, allow_redirects=False)
            status = resp.status_code
            if status in REDIRECT_STATUS:
                location = resp.headers.get("Location")
                if not location:
                    raise FetchError(
                        f"Redirect with no Location from {current}", url=current,
                        status=status)
                current = urljoin(current, location)
                await self._validate_url(current)  # fatal if internal/non-http
                continue
            if status < 400:
                self._limiter.on_success()
                return self._cap_and_decode(resp, binary)
            if status in RETRYABLE_STATUS:
                if status in RATE_LIMIT_STATUS:
                    self._limiter.on_rate_limited()
                retry_after = (parse_retry_after(resp.headers.get("Retry-After"))
                               if self.config.respect_retry_after else None)
                raise RetryableFetchError(
                    f"HTTP {status} for {current}", url=current,
                    status=status, retry_after=retry_after)
            raise FetchError(f"HTTP {status} for {current}", url=current,
                             status=status)
        raise FetchError(f"Too many redirects starting at {url}", url=url)

    async def _fetch_with_retry(self, url: str, binary: bool = False):
        assert self._session is not None
        # Validate once up front — a fatal validation error must not be retried.
        await self._validate_url(url)
        last_exc: Optional[Exception] = None

        # 1 initial attempt + max_retries retries.
        for attempt in range(1, self.config.max_retries + 2):
            retry_after: Optional[float] = None
            async with self._semaphore:
                await self._limiter.acquire()
                try:
                    return await self._request(url, binary=binary)
                except RetryableFetchError as e:
                    last_exc, retry_after = e, e.retry_after
                except NETWORK_ERRORS as e:
                    last_exc = RetryableFetchError(
                        f"Network error for {url}: {e}", url=url)

            if attempt <= self.config.max_retries:
                delay = compute_backoff(
                    attempt,
                    base=self.config.backoff_base,
                    factor=self.config.backoff_factor,
                    maximum=self.config.backoff_max,
                    retry_after=retry_after,
                )
                logger.warning("retry %d/%d for %s in %.1fs (%s)",
                               attempt, self.config.max_retries, url, delay, last_exc)
                await asyncio.sleep(delay)

        assert last_exc is not None
        raise last_exc

    # --- public API ------------------------------------------------------

    async def get_text(self, url: str, *, use_cache: bool = True) -> str:
        """Fetch a URL as text, honoring cache, robots, retries and backoff."""
        cache_path = self._cache_path(url) if use_cache else None
        if cache_path is not None and cache_path.exists():
            logger.debug("cache hit %s", url)
            return await asyncio.to_thread(cache_path.read_text, encoding="utf-8")

        if self._robots is not None and not await self._robots.allowed(url):
            raise FetchError(f"Disallowed by robots.txt: {url}", url=url)

        text = await self._fetch_with_retry(url)

        if cache_path is not None:
            try:
                await asyncio.to_thread(cache_path.write_text, text, encoding="utf-8")
            except OSError as e:
                logger.warning("failed to write cache for %s: %s", url, e)
        return text

    async def get_bytes(self, url: str) -> bytes:
        """Fetch a URL as raw bytes (e.g. a cover image). No cache; SSRF-guarded
        and size-capped.

        Tries the impersonating transport once (handles Cloudflare-protected
        images), then falls back to a plain urllib fetch — some image CDNs return
        a Content-Encoding that libcurl can't decode (curl error 61)."""
        await self._validate_url(url)
        try:
            async with self._semaphore:
                await self._limiter.acquire()
                return await self._request(url, binary=True)
        except (FetchError, *NETWORK_ERRORS) as e:
            logger.info("image fetch via curl failed (%s); falling back to urllib", e)
        return await asyncio.to_thread(self._urllib_get_bytes, url)

    def _urllib_get_bytes(self, url: str) -> bytes:
        """Blocking fallback image fetch. Redirects are refused (SSRF safety) —
        the initial host was already validated."""

        class _NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None

        opener = urllib.request.build_opener(_NoRedirect)
        req = urllib.request.Request(url, headers={
            "User-Agent": self.config.user_agent,
            "Accept": "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.8",
            "Accept-Encoding": "identity",
        })
        with opener.open(req, timeout=self.config.request_timeout) as resp:
            limit = self.config.max_response_bytes
            data = resp.read(limit + 1) if limit and limit > 0 else resp.read()
        if limit and limit > 0 and len(data) > limit:
            raise FetchError(f"Image exceeded {limit} bytes", url=url)
        return data
