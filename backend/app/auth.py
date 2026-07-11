"""Authentication: password login, server-side sessions, and invites.

A login creates a `UserSession` and returns an opaque random token delivered as
an httpOnly cookie. Only the token's SHA-256 is stored (`token_hash`), so a DB
leak can't be replayed as a cookie. Each authenticated request slides the
session's expiry. Logout / account-disable revoke immediately.

Session/invite time math uses naive-UTC (`_now`) because SQLite round-trips
datetimes without tzinfo — comparing a stored (naive) expiry against an aware
`datetime` would raise. Store and compare consistently naive here.
"""

from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone

from fastapi import Response
from sqlmodel import Session, select

from .models import Invite, User, UserSession
from .security import hash_token, new_token, verify_password
from .settings import settings

COOKIE_NAME = "ns_session"


def _now() -> datetime:
    """Naive UTC now, matching how SQLite returns stored datetimes."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _ttl() -> timedelta:
    return timedelta(days=max(1, settings.session_ttl_days))


# --- login throttle (in-memory, per-username) -------------------------------
# Single-process uvicorn, so an in-memory counter is enough to slow brute force.
_MAX_FAILS = 8
_WINDOW_SEC = 300
_fails: dict[str, list[float]] = {}


def _too_many_fails(username: str) -> bool:
    now = time.monotonic()
    hits = [t for t in _fails.get(username, []) if now - t < _WINDOW_SEC]
    _fails[username] = hits
    return len(hits) >= _MAX_FAILS


def _record_fail(username: str) -> None:
    _fails.setdefault(username, []).append(time.monotonic())


def _clear_fails(username: str) -> None:
    _fails.pop(username, None)


def authenticate(session: Session, username: str, password: str) -> User | None:
    """Return the user for valid credentials, else None. Rate-limited per user;
    a disabled account never authenticates."""
    if _too_many_fails(username):
        return None
    user = session.exec(select(User).where(User.username == username)).first()
    if user is None or user.disabled or not verify_password(password, user.password_hash):
        _record_fail(username)
        return None
    _clear_fails(username)
    return user


def create_session(session: Session, user: User) -> str:
    """Create a server-side session and return the raw cookie token."""
    token = new_token()
    session.add(UserSession(
        token_hash=hash_token(token),
        user_id=user.id,
        expires_at=_now() + _ttl(),
    ))
    session.commit()
    return token


def resolve_session(session: Session, raw_token: str) -> User | None:
    """Return the user for a valid, unexpired token, sliding the expiry. Expired
    tokens are deleted; disabled/missing users return None."""
    if not raw_token:
        return None
    row = session.get(UserSession, hash_token(raw_token))
    if row is None:
        return None
    now = _now()
    if row.expires_at <= now:
        session.delete(row)
        session.commit()
        return None
    user = session.get(User, row.user_id)
    if user is None or user.disabled:
        return None
    row.expires_at = now + _ttl()
    session.add(row)
    session.commit()
    return user


def end_session(session: Session, raw_token: str) -> None:
    """Revoke a session (logout)."""
    if not raw_token:
        return
    row = session.get(UserSession, hash_token(raw_token))
    if row is not None:
        session.delete(row)
        session.commit()


def set_session_cookie(response: Response, token: str) -> None:
    response.set_cookie(
        COOKIE_NAME, token,
        max_age=int(_ttl().total_seconds()),
        httponly=True, secure=settings.cookie_secure, samesite="lax", path="/",
    )


def clear_session_cookie(response: Response) -> None:
    response.delete_cookie(COOKIE_NAME, path="/")


# --- invites ----------------------------------------------------------------
def create_invite(session: Session, admin: User, ttl_days: int = 14) -> Invite:
    inv = Invite(
        code=new_token(16),
        created_by=admin.id,
        expires_at=_now() + timedelta(days=ttl_days),
    )
    session.add(inv)
    session.commit()
    session.refresh(inv)
    return inv


def find_valid_invite(session: Session, code: str) -> Invite | None:
    """An unused, unexpired invite for `code`, else None (no mutation)."""
    if not code:
        return None
    inv = session.get(Invite, code)
    if inv is None or inv.used_by is not None or inv.expires_at <= _now():
        return None
    return inv
