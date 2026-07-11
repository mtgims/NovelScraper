"""Shared FastAPI dependencies."""

from __future__ import annotations

from fastapi import Depends, HTTPException, Request
from sqlmodel import Session

from ..auth import COOKIE_NAME, resolve_session
from ..db import get_session
from ..jobs.manager import JobManager
from ..models import User


def get_manager(request: Request) -> JobManager:
    return request.app.state.manager


def get_current_user(
    request: Request, session: Session = Depends(get_session)
) -> User:
    """Resolve the authenticated user from the session cookie, or 401.

    Applied as a router-level dependency to every data router in main.py, so no
    endpoint is reachable without a valid session."""
    user = resolve_session(session, request.cookies.get(COOKIE_NAME, ""))
    if user is None:
        raise HTTPException(status_code=401, detail="Not authenticated")
    return user


def get_admin(user: User = Depends(get_current_user)) -> User:
    """Require the authenticated user to be an admin (else 403)."""
    if not user.is_admin:
        raise HTTPException(status_code=403, detail="Admin only")
    return user
