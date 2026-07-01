"""In-memory per-job progress event bus for SSE fan-out.

Subscribers (SSE connections) each get their own unbounded queue. Publishing is
synchronous (``put_nowait``) so it can be called from the scraper's progress
callback, which runs on the event loop thread. Events are ephemeral — durable
state lives in the database; the bus only carries live updates.
"""

from __future__ import annotations

import asyncio
from typing import Dict, List


class EventBus:
    def __init__(self) -> None:
        self._subscribers: Dict[str, List[asyncio.Queue]] = {}

    def subscribe(self, job_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue()
        self._subscribers.setdefault(job_id, []).append(queue)
        return queue

    def unsubscribe(self, job_id: str, queue: asyncio.Queue) -> None:
        subs = self._subscribers.get(job_id)
        if not subs:
            return
        if queue in subs:
            subs.remove(queue)
        if not subs:
            self._subscribers.pop(job_id, None)

    def publish(self, job_id: str, event: dict) -> None:
        for queue in self._subscribers.get(job_id, []):
            queue.put_nowait(event)
