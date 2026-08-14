"""Run configuration for a scrape.

`delay` controls the request *rate* (minimum interval between request starts,
plus jitter); `max_concurrency` caps how many requests are in flight at once.
With a slow site the concurrency cap lets waits overlap; the rate limiter is
what keeps the overall request rate polite.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, Optional, Union

import yaml

# A realistic, current desktop UA. Honest pacing + robots handling matter more
# than UA rotation; we keep a single plausible UA rather than spoofing many.
DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)


@dataclass
class ScraperConfig:
    # Pacing / politeness.
    # delay is the min seconds between request *starts*; combined with
    # max_concurrency it bounds load. A small default keeps throughput high
    # (concurrency-driven) while staying gentler than no spacing at all.
    # Raise delay (or lower concurrency) if a site rate-limits you.
    delay: float = 0.1            # starting interval between request starts
    jitter: float = 0.5           # extra random delay, as a fraction of `delay`
    max_concurrency: int = 12     # max simultaneous in-flight requests
    # Adaptive pacing: the interval speeds up while requests succeed and backs
    # off on 429/503, converging on the fastest rate a site tolerates without
    # manual tuning. max_interval caps how slow it can get.
    adaptive_pacing: bool = True
    max_interval: float = 8.0

    # Retry / backoff
    max_retries: int = 4          # retries *after* the initial attempt
    backoff_base: float = 1.0
    backoff_factor: float = 2.0
    backoff_max: float = 60.0     # <= 0 means no cap
    respect_retry_after: bool = True

    # HTTP
    request_timeout: float = 30.0
    user_agent: str = DEFAULT_USER_AGENT  # used for robots.txt matching
    # Browser TLS fingerprint to impersonate (curl_cffi) so Cloudflare-protected
    # sites that block plain Python HTTP clients still work.
    impersonate: str = "chrome"
    max_response_bytes: int = 10 * 1024 * 1024  # per-response cap; <= 0 = unlimited

    # Safety
    # When False (default) the fetcher refuses to connect to private/loopback/
    # link-local/reserved IPs, mitigating SSRF once untrusted slugs/profiles
    # reach the API. Set True only for local testing against 127.0.0.1.
    allow_private_hosts: bool = False

    # Politeness policy
    respect_robots: bool = True

    # Output / cache
    output_dir: str = "data/output"
    cache_dir: Optional[str] = "data/cache"  # None disables on-disk caching
    cover_dir: Optional[str] = None          # where downloaded covers are saved

    # Packaging
    chapters_per_volume: int = 100

    # User-IP relay: when set to a user id AND that user's phone holds a relay
    # WebSocket, the fetcher routes each HTTP hop through the phone (its IP) so
    # Cloudflare datacenter-ASN blocks don't apply. Falls back to a server fetch
    # when no relay is connected. None = always fetch server-side (old behaviour).
    relay_user_id: Optional[int] = None

    def __post_init__(self) -> None:
        self.validate()

    def validate(self) -> None:
        if self.delay < 0:
            raise ValueError("delay must be >= 0")
        if self.jitter < 0:
            raise ValueError("jitter must be >= 0")
        if self.max_concurrency < 1:
            raise ValueError("max_concurrency must be >= 1")
        if self.max_retries < 0:
            raise ValueError("max_retries must be >= 0")
        if self.backoff_factor < 1:
            raise ValueError("backoff_factor must be >= 1")
        if self.request_timeout <= 0:
            raise ValueError("request_timeout must be > 0")
        if self.chapters_per_volume < 1:
            raise ValueError("chapters_per_volume must be >= 1")

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ScraperConfig":
        known = set(cls.__dataclass_fields__)
        unknown = set(data) - known
        if unknown:
            raise ValueError(f"Unknown config keys: {sorted(unknown)}")
        return cls(**data)

    @classmethod
    def from_file(cls, path: Union[str, Path]) -> "ScraperConfig":
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
        if not isinstance(data, dict):
            raise ValueError(f"Config file {path} must contain a mapping")
        return cls.from_dict(data)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)
