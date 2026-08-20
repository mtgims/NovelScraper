"""Unit tests for the auto-update queueing (app/updater.py).

Regression guard for the bug where the background loop called manager.submit()
WITHOUT user_id (required since multi-user), so every auto-update raised TypeError
and was silently swallowed — nothing ever updated. Uses a stub manager so no real
scrape runs; only the queueing decisions and the owner passed to submit() matter.
"""
from __future__ import annotations

import os
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sqlalchemy.pool import StaticPool
from sqlmodel import Session, SQLModel, create_engine

import app.models  # noqa: F401 — register tables
from app.jobs.manager import DuplicateJobError
from app.models import Book, User
from app.updater import queue_due_updates

_passed = 0
_failed = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global _passed, _failed
    if cond:
        _passed += 1
        print(f"[{name}] PASS {detail}")
    else:
        _failed += 1
        print(f"[{name}] FAIL {detail}")


class StubManager:
    """Records submit() calls; can simulate an already-running job per URL."""

    def __init__(self, dup_urls=()):
        self.calls = []  # (url, user_id, incremental)
        self.dup_urls = set(dup_urls)

    def submit(self, data, user_id, incremental=False):
        if data.url in self.dup_urls:
            raise DuplicateJobError()
        self.calls.append((data.url, user_id, incremental))
        return object()


def make_engine():
    e = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    SQLModel.metadata.create_all(e)
    return e


def seed(engine):
    now = datetime.now(timezone.utc)
    old = now - timedelta(hours=24)
    recent = now - timedelta(hours=1)
    ids = {}
    with Session(engine) as s:
        u1 = User(username="alice", password_hash="x")
        u2 = User(username="bob", password_hash="x")
        s.add(u1)
        s.add(u2)
        s.commit()
        s.refresh(u1)
        s.refresh(u2)
        ids["u1"], ids["u2"] = u1.id, u2.id
        s.add(Book(slug="due1", site="s", source_url="http://s/due1", updated_at=old, user_id=u1.id))
        s.add(Book(slug="never", site="s", source_url="http://s/never", updated_at=None, user_id=u1.id))
        s.add(Book(slug="recent", site="s", source_url="http://s/recent", updated_at=recent, user_id=u1.id))
        s.add(Book(slug="imported", site="s", source_url=None, updated_at=old, user_id=u1.id, imported=True))
        s.add(Book(slug="due2", site="s", source_url="http://s/due2", updated_at=old, user_id=u2.id))
        s.commit()
    return ids


def main() -> int:
    # 1. All users: due + never-scraped queue with the correct owner; recent and
    #    source-less (imported) books are skipped. This is the shipped bug.
    e = make_engine()
    ids = seed(e)
    m = StubManager()
    n = queue_due_updates(m, e, hours=6)
    by_url = {url: (uid, inc) for url, uid, inc in m.calls}
    check("all-count", n == 3, f"queued={n} calls={m.calls}")
    check("owner-due1", by_url.get("http://s/due1") == (ids["u1"], True))
    check("owner-never", by_url.get("http://s/never") == (ids["u1"], True))
    check("owner-due2", by_url.get("http://s/due2") == (ids["u2"], True))
    check("skip-recent", "http://s/recent" not in by_url)
    check("skip-imported", "http://s/imported" not in by_url and len(m.calls) == 3)

    # 2. Per-user scope: only that user's due books (no cross-user leak).
    e = make_engine()
    ids = seed(e)
    m = StubManager()
    n = queue_due_updates(m, e, hours=6, user_id=ids["u1"])
    check("peruser-count", n == 2, f"queued={n}")
    check("peruser-owner", all(uid == ids["u1"] for _, uid, _ in m.calls))
    check("peruser-no-u2", all(url != "http://s/due2" for url, _, _ in m.calls))

    # 3. Disabled (hours=0) queues nothing.
    e = make_engine()
    seed(e)
    m = StubManager()
    n = queue_due_updates(m, e, hours=0)
    check("disabled", n == 0 and m.calls == [])

    # 4. An already-running job (DuplicateJobError) is skipped, not fatal.
    e = make_engine()
    seed(e)
    m = StubManager(dup_urls={"http://s/due1"})
    n = queue_due_updates(m, e, hours=6)
    check("dup-skipped", n == 2 and all(url != "http://s/due1" for url, _, _ in m.calls))

    # 5. attempt_log throttle: a book attempted within the interval is skipped, and
    #    the log is populated for books that are attempted.
    e = make_engine()
    seed(e)
    log = {}
    with Session(e) as s:
        due1_id = s.exec(__import__("sqlmodel").select(Book).where(Book.slug == "due1")).first().id
    log[due1_id] = datetime.now(timezone.utc)  # pretend just attempted
    m = StubManager()
    n = queue_due_updates(m, e, hours=6, attempt_log=log)
    check("throttle-skip", all(url != "http://s/due1" for url, _, _ in m.calls),
          f"calls={m.calls}")
    check("throttle-records", len(log) >= 3)

    print(f"\nSUMMARY: {_passed}/{_passed + _failed} passed")
    return 1 if _failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
