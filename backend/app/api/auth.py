"""Authentication & account-management endpoints (`/api/auth/*`).

Public: login, register (invite-gated unless open signup is on). Authenticated:
logout, me. Admin-only: invites + user management. The heavy lifting (sessions,
cookies, invites, throttling) lives in `app.auth`; this module is just HTTP glue.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from sqlmodel import Session, select

from .. import auth as auth_svc
from ..db import get_session
from ..models import Invite, User
from ..schemas import (
    AuthConfig,
    InviteRead,
    LoginRequest,
    RegisterRequest,
    UserAdminUpdate,
    UserRead,
)
from ..security import hash_password
from ..services import accounts
from ..store import get_allow_open_signup
from .deps import get_admin, get_current_user

router = APIRouter()


@router.get("/config", response_model=AuthConfig)
def auth_config():
    """Public pre-login config so the register page knows whether an invite is
    required. (Deliberately reveals nothing sensitive.)"""
    return AuthConfig(allow_open_signup=get_allow_open_signup())


@router.post("/login", response_model=UserRead)
def login(body: LoginRequest, response: Response,
          session: Session = Depends(get_session)):
    user = auth_svc.authenticate(session, body.username, body.password)
    if user is None:
        raise HTTPException(status_code=401, detail="Invalid username or password")
    auth_svc.set_session_cookie(response, auth_svc.create_session(session, user))
    return user


@router.post("/logout", status_code=204)
def logout(request: Request, response: Response,
           session: Session = Depends(get_session)):
    auth_svc.end_session(session, request.cookies.get(auth_svc.COOKIE_NAME, ""))
    auth_svc.clear_session_cookie(response)


@router.get("/me", response_model=UserRead)
def me(user: User = Depends(get_current_user)):
    return user


@router.post("/register", response_model=UserRead)
def register(body: RegisterRequest, response: Response,
             session: Session = Depends(get_session)):
    # Invite-gated unless open signup is enabled. Validate the invite before
    # creating anything so a bad code can't leave a half-registered account.
    invite = None
    if not get_allow_open_signup():
        invite = auth_svc.find_valid_invite(session, body.invite_code or "")
        if invite is None:
            raise HTTPException(status_code=403, detail="Invalid or expired invite code")
    if session.exec(select(User).where(User.username == body.username)).first():
        raise HTTPException(status_code=409, detail="That username is taken")

    user = User(username=body.username, password_hash=hash_password(body.password))
    session.add(user)
    session.commit()
    session.refresh(user)
    if invite is not None:
        invite.used_by = user.id
        session.add(invite)
        session.commit()
    auth_svc.set_session_cookie(response, auth_svc.create_session(session, user))
    return user


# --- admin: invites & user management ---------------------------------------
@router.post("/invites", response_model=InviteRead)
def create_invite(admin: User = Depends(get_admin),
                  session: Session = Depends(get_session)):
    return auth_svc.create_invite(session, admin)


@router.get("/invites", response_model=List[InviteRead])
def list_invites(admin: User = Depends(get_admin),
                 session: Session = Depends(get_session)):
    return session.exec(select(Invite).order_by(Invite.created_at.desc())).all()


@router.delete("/invites")
def clear_spent_invites(admin: User = Depends(get_admin),
                        session: Session = Depends(get_session)):
    """Delete used or expired invites, keeping still-usable (active) ones."""
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    spent = session.exec(
        select(Invite).where(
            (Invite.used_by.is_not(None)) | (Invite.expires_at <= now)
        )
    ).all()
    for inv in spent:
        session.delete(inv)
    session.commit()
    return {"deleted": len(spent)}


@router.delete("/invites/{code}", status_code=204)
def delete_invite(code: str, admin: User = Depends(get_admin),
                  session: Session = Depends(get_session)):
    """Delete (revoke) a single invite by code."""
    inv = session.get(Invite, code)
    if inv is None:
        raise HTTPException(status_code=404, detail="Invite not found")
    session.delete(inv)
    session.commit()


@router.get("/users", response_model=List[UserRead])
def list_users(admin: User = Depends(get_admin),
               session: Session = Depends(get_session)):
    return session.exec(select(User).order_by(User.created_at)).all()


@router.patch("/users/{user_id}", response_model=UserRead)
def update_user(user_id: int, body: UserAdminUpdate,
                admin: User = Depends(get_admin),
                session: Session = Depends(get_session)):
    target = session.get(User, user_id)
    if target is None:
        raise HTTPException(status_code=404, detail="User not found")

    # Don't let the last active admin be demoted or disabled — that would lock
    # everyone out of account management.
    demoting = body.is_admin is False and target.is_admin
    disabling = body.disabled is True and not target.disabled
    if (demoting or disabling) and target.is_admin and not target.disabled:
        others = session.exec(
            select(User).where(User.is_admin == True,  # noqa: E712
                               User.disabled == False,  # noqa: E712
                               User.id != target.id)
        ).first()
        if others is None:
            raise HTTPException(status_code=400,
                                detail="Can't remove the last active admin")

    if body.disabled is not None:
        target.disabled = body.disabled
    if body.is_admin is not None:
        target.is_admin = body.is_admin
    session.add(target)
    session.commit()
    session.refresh(target)
    return target


@router.delete("/users/{user_id}", status_code=204)
def delete_user(user_id: int, admin: User = Depends(get_admin),
                session: Session = Depends(get_session)):
    """Delete an account and ALL of its data (library, files, sessions). Can't
    delete your own account or the last active admin."""
    target = session.get(User, user_id)
    if target is None:
        raise HTTPException(status_code=404, detail="User not found")
    if target.id == admin.id:
        raise HTTPException(status_code=400, detail="You can't delete your own account")
    if target.is_admin and not target.disabled:
        other = session.exec(
            select(User).where(User.is_admin == True,  # noqa: E712
                               User.disabled == False,  # noqa: E712
                               User.id != target.id)
        ).first()
        if other is None:
            raise HTTPException(status_code=400,
                                detail="Can't delete the last active admin")
    accounts.delete_user(session, target.id)
