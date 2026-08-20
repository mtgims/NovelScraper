"""Optional background auto-update: periodically re-scrape books to pull new
chapters. Disabled unless the auto-update interval is > 0. The manual per-book
"Update" button (POST /books/{id}/update) works regardless of this setting, as
does the app-triggered POST /books/update-due (which routes through the phone
relay so Cloudflare-gated sources can be fetched from a residential IP).

Re-scraping reuses the normal job pipeline; the fetcher's disk cache means only
new/changed pages hit the network. Rating, collections and reading progress live
on the book row and survive the re-scrape.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlmodel import Session, select

from .jobs.manager import DuplicateJobError, JobManager
from .models import Book
from .schemas import JobCreate
from .store import get_auto_update_hours

logger = logging.getLogger(__name__)

# How often the loop wakes to check whether any book is due; the actual update
# cadence is the configured auto_update_hours (read fresh each wake, so changing
# it in Settings takes effect within this window).
_CHECK_INTERVAL_SECONDS = 900

# book id -> last time the background loop *attempted* an update. Books that keep
# failing (e.g. a Cloudflare-gated source 403ing from the server) don't advance
# their updated_at, so without this they'd be re-submitted every wake; skip them
# until a full interval has passed. In-memory only (cleared on restart).
_last_attempt: dict[int, datetime] = {}


def _aware(dt: datetime | None) -> datetime | None:
    """SQLite round-trips datetimes as naive; treat them as UTC for comparison."""
    if dt is None:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def queue_due_updates(
    manager: JobManager,
    engine,
    hours: int,
    user_id: Optional[int] = None,
    attempt_log: Optional[dict[int, datetime]] = None,
) -> int:
    """Submit an incremental re-scrape for every book not scraped within the last
    `hours`. Scoped to `user_id` when given (else all users). Each job is owned by
    the book's owner. When `attempt_log` is given, a book attempted within `hours`
    is skipped and its attempt time recorded (used by the background loop to avoid
    hammering perpetually-failing sources). Returns the number queued."""
    if hours <= 0:
        return 0
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=hours)
    with Session(engine) as s:
        q = select(Book).where(Book.source_url.is_not(None))
        if user_id is not None:
            q = q.where(Book.user_id == user_id)
        # Materialise the fields we need inside the session (avoid detached access).
        due = [
            (b.id, b.user_id, b.source_url)
            for b in s.exec(q).all()
            if (u := _aware(b.updated_at)) is None or u <= cutoff
        ]
    queued = 0
    for book_id, owner_id, url in due:
        if attempt_log is not None:
            last = attempt_log.get(book_id)
            if last is not None and last > cutoff:
                continue  # attempted recently (likely failing) — don't re-hammer
            attempt_log[book_id] = now
        try:
            manager.submit(JobCreate(url=url), user_id=owner_id, incremental=True)
            queued += 1
        except DuplicateJobError:
            pass  # already updating
        except Exception:  # noqa: BLE001 — one bad book shouldn't stop the pass
            logger.exception("auto-update: submit failed for book %s", book_id)
    if queued:
        logger.info("auto-update queued %d book(s)", queued)
    return queued


async def auto_update_loop(manager: JobManager, engine) -> None:
    await asyncio.sleep(60)  # let startup settle before the first pass
    while True:
        try:
            hours = get_auto_update_hours()  # re-read so Settings changes apply
            queue_due_updates(manager, engine, hours, attempt_log=_last_attempt)
        except Exception:  # noqa: BLE001
            logger.exception("auto-update pass failed")
        await asyncio.sleep(_CHECK_INTERVAL_SECONDS)
