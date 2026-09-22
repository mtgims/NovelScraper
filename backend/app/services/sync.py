"""Library metadata sync between a user's devices.

The apps keep the library on the device and sync only metadata, as small
records. Each record is (kind, key) -> JSON value, written with the device's
hybrid clock `ts` (ms) and its id; the server keeps, per record, the write with
the highest (ts, device) and numbers every accepted write with a growing `seq`,
so a device asks for "everything after the last seq I saw".

Record kinds and keys. A novel key is ``srv:<book id>`` for a novel stored on
this server, or ``src:<plugin id>\\t<path>`` for one read from a source
extension. A collection key is ``srv:<collection id>`` for one that existed on
the server before sync, else a random id from the app.

    novel       <novel>                  {plugin, path, server_id, title, author,
                                          cover, site, in_library}
    order       <novel>                  {sort}
    rating      <novel>                  {rating}            (null = unrated)
    progress    <novel>                  {chapter, scroll, sentence}
    read        <novel>\\t<chapter path>  {read}
    collection  <collection>             {name, sort, deleted}
    shelf       <collection>\\t<novel>    {member}

Chapter paths are the source's own; for a server novel, the chapter position.

Novels stored on the server keep their old tables (Book.rating, ReadingProgress,
collections) in step with the records (see `_mirror`), so the server's stats and
the web app still see them. The first sync of an account seeds records from
those tables (`seed_user`), at the lowest clock, so anything a device sends wins.
"""

from __future__ import annotations

import json
import time
from typing import Iterable, List, Optional

from sqlalchemy import func
from sqlmodel import Session, select

from ..models import (Book, BookCollectionLink, Collection, ReadingProgress,
                      Setting, SyncRecord, User)
from ..schemas import SyncChange

KINDS = {"novel", "order", "rating", "progress", "read", "collection", "shelf"}
MAX_KEY = 2048
MAX_VALUE = 16 * 1024
MAX_CHANGES = 2000
PAGE = 2000

SEED_TS = 1            # below any real clock: a device's write always wins
SEED_DEVICE = "seed"
SERVER_DEVICE = "server"
_SEQ_KEY = "sync_seq"


class SyncError(ValueError):
    pass


def now_ms() -> int:
    return int(time.time() * 1000)


def _newer(ts: int, device: str, than_ts: int, than_device: str) -> bool:
    return (ts, device) > (than_ts, than_device)


def _next_seqs(session: Session, n: int) -> int:
    """Reserve n seqs; returns the first. The UPDATE takes SQLite's write lock
    at once, so concurrent syncs number (and commit) their writes in order."""
    row = session.get(Setting, _SEQ_KEY)
    if row is None:
        start = (session.exec(select(func.max(SyncRecord.seq))).one() or 0) + 1
        row = Setting(key=_SEQ_KEY, value=str(start - 1))
    first = int(row.value) + 1
    row.value = str(first + n - 1)
    session.add(row)
    session.flush()
    return first


def _validate(c: SyncChange) -> None:
    if c.kind not in KINDS:
        raise SyncError(f"unknown kind {c.kind!r}")
    if not c.key or len(c.key) > MAX_KEY:
        raise SyncError("bad key")
    if len(c.value) > MAX_VALUE:
        raise SyncError("value too large")
    try:
        json.loads(c.value)
    except ValueError:
        raise SyncError("value is not JSON") from None
    if c.ts < 0:
        raise SyncError("bad ts")


def apply_changes(session: Session, user: User, device: str,
                  changes: List[SyncChange]) -> int:
    """Store the changes that win over what the server has. Returns how many
    were accepted. Commits."""
    if len(changes) > MAX_CHANGES:
        raise SyncError(f"at most {MAX_CHANGES} changes per call")
    for c in changes:
        _validate(c)
    if not changes:
        return 0
    seq = _next_seqs(session, len(changes))
    accepted = 0
    for c in changes:
        dev = c.device or device
        rec = session.exec(select(SyncRecord).where(
            SyncRecord.user_id == user.id, SyncRecord.kind == c.kind, SyncRecord.key == c.key,
        )).first()
        if rec is not None and not _newer(c.ts, dev, rec.ts, rec.device):
            continue
        if rec is None:
            rec = SyncRecord(user_id=user.id, kind=c.kind, key=c.key, value=c.value,
                             ts=c.ts, device=dev, seq=seq)
        else:
            rec.value, rec.ts, rec.device, rec.seq = c.value, c.ts, dev, seq
        seq += 1
        session.add(rec)
        _mirror(session, user, rec)
        accepted += 1
    session.commit()
    return accepted


def changes_since(session: Session, user: User, cursor: int, limit: int = PAGE):
    """Records written after `cursor`, oldest first: (records, new cursor, more)."""
    rows = session.exec(
        select(SyncRecord)
        .where(SyncRecord.user_id == user.id, SyncRecord.seq > cursor)
        .order_by(SyncRecord.seq)
        .limit(limit + 1)
    ).all()
    more = len(rows) > limit
    rows = rows[:limit]
    return rows, (rows[-1].seq if rows else cursor), more


def server_write(session: Session, user_id: int, kind: str, key: str, value: dict) -> None:
    """A change made by the server itself (e.g. a novel deleted here), sent to
    the devices like any other. Does not commit."""
    user = session.get(User, user_id)
    if user is None:
        return
    seq = _next_seqs(session, 1)
    body = json.dumps(value, separators=(",", ":"))
    rec = session.exec(select(SyncRecord).where(
        SyncRecord.user_id == user_id, SyncRecord.kind == kind, SyncRecord.key == key,
    )).first()
    ts = max(now_ms(), (rec.ts + 1) if rec else 0)
    if rec is None:
        rec = SyncRecord(user_id=user_id, kind=kind, key=key, value=body,
                         ts=ts, device=SERVER_DEVICE, seq=seq)
    else:
        rec.value, rec.ts, rec.device, rec.seq = body, ts, SERVER_DEVICE, seq
    session.add(rec)


# --- first sync: records from the server's own tables -----------------------------

def _seeded_key(user_id: int) -> str:
    return f"sync_seeded:{user_id}"


def seed_user(session: Session, user: User) -> int:
    """Give an account that never synced its server library as records, once.
    Returns the number of records made. Commits."""
    if session.get(Setting, _seeded_key(user.id)) is not None:
        return 0
    recs: list[tuple[str, str, dict]] = []
    books = session.exec(select(Book).where(Book.user_id == user.id)).all()
    for b in books:
        nk = f"srv:{b.id}"
        recs.append(("novel", nk, {
            "plugin": None, "path": None, "server_id": b.id, "title": b.title,
            "author": b.author, "cover": None, "site": b.site, "in_library": True,
        }))
        recs.append(("order", nk, {"sort": b.sort_order}))
        if b.rating is not None:
            recs.append(("rating", nk, {"rating": b.rating}))
        prog = session.exec(select(ReadingProgress).where(ReadingProgress.book_id == b.id)).first()
        if prog is not None:
            recs.append(("progress", nk, {"chapter": str(prog.last_position),
                                          "scroll": prog.scroll, "sentence": None}))
            for p in prog.read_positions or []:
                recs.append(("read", f"{nk}\t{p}", {"read": True}))
    cols = session.exec(select(Collection).where(Collection.user_id == user.id)).all()
    col_ids = {c.id for c in cols}
    for c in cols:
        recs.append(("collection", f"srv:{c.id}", {"name": c.name, "sort": c.sort_order, "deleted": False}))
    if col_ids:
        links = session.exec(select(BookCollectionLink).where(
            BookCollectionLink.collection_id.in_(col_ids))).all()
        for link in links:
            recs.append(("shelf", f"srv:{link.collection_id}\tsrv:{link.book_id}", {"member": True}))

    if recs:
        seq = _next_seqs(session, len(recs))
        for i, (kind, key, value) in enumerate(recs):
            exists = session.exec(select(SyncRecord.id).where(
                SyncRecord.user_id == user.id, SyncRecord.kind == kind, SyncRecord.key == key)).first()
            if exists is None:
                session.add(SyncRecord(user_id=user.id, kind=kind, key=key,
                                       value=json.dumps(value, separators=(",", ":")),
                                       ts=SEED_TS, device=SEED_DEVICE, seq=seq + i))
    session.add(Setting(key=_seeded_key(user.id), value="1"))
    session.commit()
    return len(recs)


# --- keeping the server's own tables in step -------------------------------------------

def _server_id(novel_key: str) -> Optional[int]:
    if not novel_key.startswith("srv:"):
        return None
    try:
        return int(novel_key[4:])
    except ValueError:
        return None


def _owned(session: Session, user: User, book_id: Optional[int]) -> Optional[Book]:
    if book_id is None:
        return None
    b = session.get(Book, book_id)
    return b if b is not None and b.user_id == user.id else None


def _progress_row(session: Session, book_id: int) -> ReadingProgress:
    prog = session.exec(select(ReadingProgress).where(ReadingProgress.book_id == book_id)).first()
    return prog or ReadingProgress(book_id=book_id)


def _mirror(session: Session, user: User, rec: SyncRecord) -> None:
    """Apply a record about a novel or collection stored on this server to the
    server's own tables. Removing a novel from the library never deletes it here
    (that is the delete endpoint's job)."""
    v = json.loads(rec.value)
    if rec.kind in ("rating", "order", "progress"):
        b = _owned(session, user, _server_id(rec.key))
        if b is None:
            return
        if rec.kind == "rating":
            r = v.get("rating")
            b.rating = r if isinstance(r, int) and 1 <= r <= 5 else None
            session.add(b)
        elif rec.kind == "order":
            if isinstance(v.get("sort"), (int, float)):
                b.sort_order = int(v["sort"])
                session.add(b)
        else:
            prog = _progress_row(session, b.id)
            try:
                prog.last_position = int(v.get("chapter"))
            except (TypeError, ValueError):
                pass
            if isinstance(v.get("scroll"), (int, float)):
                prog.scroll = float(v["scroll"])
            session.add(prog)
    elif rec.kind == "read":
        novel, _, chapter = rec.key.rpartition("\t")
        b = _owned(session, user, _server_id(novel))
        if b is None:
            return
        try:
            pos = int(chapter)
        except ValueError:
            return
        prog = _progress_row(session, b.id)
        current = set(prog.read_positions or [])
        if v.get("read"):
            current.add(pos)
        else:
            current.discard(pos)
        prog.read_positions = sorted(current)   # reassign so the JSON change is seen
        session.add(prog)
    elif rec.kind == "collection":
        cid = _server_id(rec.key)
        col = session.get(Collection, cid) if cid is not None else None
        if col is None or col.user_id != user.id:
            return
        if v.get("deleted"):
            for link in session.exec(select(BookCollectionLink).where(
                    BookCollectionLink.collection_id == col.id)).all():
                session.delete(link)
            session.delete(col)
        else:
            if isinstance(v.get("name"), str) and v["name"].strip():
                col.name = v["name"].strip()
            session.add(col)
    elif rec.kind == "shelf":
        ckey, _, nkey = rec.key.partition("\t")
        cid = _server_id(ckey)
        col = session.get(Collection, cid) if cid is not None else None
        b = _owned(session, user, _server_id(nkey))
        if col is None or col.user_id != user.id or b is None:
            return
        link = session.get(BookCollectionLink, (b.id, col.id))
        if v.get("member") and link is None:
            session.add(BookCollectionLink(book_id=b.id, collection_id=col.id))
        elif not v.get("member") and link is not None:
            session.delete(link)


def as_changes(rows: Iterable[SyncRecord]) -> List[SyncChange]:
    return [SyncChange(kind=r.kind, key=r.key, value=r.value, ts=r.ts, device=r.device) for r in rows]
