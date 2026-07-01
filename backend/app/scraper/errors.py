"""Exception taxonomy for the scraper core.

The retry layer distinguishes *retryable* failures (transient: timeouts,
429/5xx) from *fatal* ones (4xx other than 429, disallowed by robots, bad
selectors). Only retryable failures are backed off and retried.
"""

from __future__ import annotations

from typing import Optional


class ScraperError(Exception):
    """Base class for all scraper errors."""


class FetchError(ScraperError):
    """A non-retryable HTTP/network failure (e.g. 403, 404, robots-disallowed)."""

    def __init__(self, message: str, *, url: Optional[str] = None,
                 status: Optional[int] = None) -> None:
        super().__init__(message)
        self.url = url
        self.status = status


class RetryableFetchError(FetchError):
    """A transient failure (timeout, 429/5xx) eligible for retry.

    Carries an optional ``retry_after`` (seconds) parsed from the response so
    the retry layer can honor a server-provided backoff hint.
    """

    def __init__(self, message: str, *, url: Optional[str] = None,
                 status: Optional[int] = None,
                 retry_after: Optional[float] = None) -> None:
        super().__init__(message, url=url, status=status)
        self.retry_after = retry_after


class ContentNotFoundError(ScraperError):
    """A page was fetched but the expected content/selectors were missing."""


class ProfileError(ScraperError):
    """A site profile is missing required fields or otherwise invalid."""
