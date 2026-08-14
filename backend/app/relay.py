"""User-IP scrape relay.

Some sources (Cloudflare datacenter-ASN blocks) 403 the server's IP but not a
residential/mobile one. When a user's phone (the native app) holds a WebSocket to
``/api/relay``, the scraper's single fetch choke point (``AsyncFetcher._request``)
routes the raw HTTP GET/POST to that phone instead of fetching server-side: the
phone fetches from *its* IP and returns status + headers + body. The server still
does everything else (enumeration, redirect-following, SSRF validation, robots,
pacing, caching, parsing) — only the network hop moves.

Deliberately dependency-light (no scraper/db imports) so ``fetcher`` can import
the hub without a cycle. Assumes a single worker process (the deploy runs one
uvicorn worker), so the job task and the WebSocket share this in-process hub.
"""

from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass
from typing import Dict, Optional

RELAY_TIMEOUT = 45.0  # seconds to wait for the phone to return one fetch


class RelayError(Exception):
    """A relay fetch failed (phone error, disconnect, or timeout)."""


class RelayUnavailable(RelayError):
    """No phone relay is connected for the user."""


class _CIHeaders:
    """Minimal case-insensitive header view (the fetcher reads Location /
    Retry-After / RateLimit-* via ``.get``, curl_cffi-style)."""

    def __init__(self, d: Optional[dict]) -> None:
        self._d = {str(k).lower(): v for k, v in (d or {}).items()}

    def get(self, key: str, default=None):
        return self._d.get(str(key).lower(), default)


@dataclass
class RelayResponse:
    """Response-shaped object the fetcher's ``_request`` already knows how to
    handle (status_code / headers / content / encoding / url)."""

    status_code: int
    headers: _CIHeaders
    content: bytes
    url: str
    encoding: Optional[str] = None


class RelayConnection:
    """One connected phone. Correlates requests to replies by id over the socket."""

    def __init__(self, ws) -> None:
        self._ws = ws
        self._pending: Dict[int, asyncio.Future] = {}
        self._send_lock = asyncio.Lock()  # Starlette send isn't concurrency-safe
        self._counter = 0

    def resolve(self, reply: dict) -> None:
        """Called by the WebSocket receive loop for each reply frame."""
        try:
            rid = int(reply.get("id"))
        except (TypeError, ValueError):
            return
        fut = self._pending.get(rid)
        if fut is not None and not fut.done():
            fut.set_result(reply)

    def fail_all(self) -> None:
        for fut in list(self._pending.values()):
            if not fut.done():
                fut.set_exception(RelayError("relay disconnected"))
        self._pending.clear()

    async def fetch(self, url: str, method: str, headers: dict,
                    data: Optional[dict]) -> RelayResponse:
        loop = asyncio.get_running_loop()
        self._counter += 1
        rid = self._counter
        fut: asyncio.Future = loop.create_future()
        self._pending[rid] = fut
        msg = {"id": rid, "url": url, "method": method, "headers": headers or {}}
        if data is not None:
            msg["data"] = data
        try:
            async with self._send_lock:
                await self._ws.send_json(msg)
            reply = await asyncio.wait_for(fut, timeout=RELAY_TIMEOUT)
        except asyncio.TimeoutError:
            raise RelayError(f"relay fetch timed out for {url}")
        finally:
            self._pending.pop(rid, None)
        if not reply.get("ok"):
            raise RelayError(str(reply.get("error") or "relay fetch failed"))
        body = base64.b64decode(reply.get("body_b64") or "")
        return RelayResponse(
            status_code=int(reply.get("status", 0)),
            headers=_CIHeaders(reply.get("headers")),
            content=body,
            url=str(reply.get("url") or url),
        )


class RelayHub:
    """Registry of user_id -> connected phone (latest wins)."""

    def __init__(self) -> None:
        self._conns: Dict[int, RelayConnection] = {}

    def register(self, user_id: int, conn: RelayConnection) -> None:
        self._conns[user_id] = conn

    def unregister(self, user_id: int, conn: RelayConnection) -> None:
        if self._conns.get(user_id) is conn:
            self._conns.pop(user_id, None)

    def is_connected(self, user_id: int) -> bool:
        return user_id in self._conns

    async def fetch(self, user_id: int, url: str, method: str, headers: dict,
                    data: Optional[dict]) -> RelayResponse:
        conn = self._conns.get(user_id)
        if conn is None:
            raise RelayUnavailable("no relay connected")
        return await conn.fetch(url, method, headers, data)


hub = RelayHub()
