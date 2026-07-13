"""Shared FastAPI dependencies."""

from __future__ import annotations

from fastapi import Depends, HTTPException, Request, Response
from sqlmodel import Session

from ..auth import COOKIE_NAME, resolve_session, set_session_cookie
from ..db import get_session
from ..jobs.manager import JobManager
from ..models import User


def get_manager(request: Request) -> JobManager:
    return request.app.state.manager


def get_current_user(
    request: Request, response: Response, session: Session = Depends(get_session)
) -> User:
    """Resolve the authenticated user from the session cookie, or 401.

    Applied as a router-level dependency to every data router in main.py, so no
    endpoint is reachable without a valid session.

    Also **slides the cookie** in step with the DB session (resolve_session
    already extended the server-side expiry). Without this the cookie keeps its
    original login max-age and would be dropped by the browser at that absolute
    deadline — 401-ing an actively-reading user mid-session even though their
    session is still valid. Endpoints that return a FileResponse directly (cover/
    image/audio) skip this, but the JSON endpoints hit constantly while reading —
    chapter loads, progress saves, the audio manifest — keep the cookie fresh."""
    token = request.cookies.get(COOKIE_NAME, "")
    user = resolve_session(session, token)
    if user is None:
        raise HTTPException(status_code=401, detail="Not authenticated")
    set_session_cookie(response, token)
    return user


def get_admin(user: User = Depends(get_current_user)) -> User:
    """Require the authenticated user to be an admin (else 403)."""
    if not user.is_admin:
        raise HTTPException(status_code=403, detail="Admin only")
    return user
