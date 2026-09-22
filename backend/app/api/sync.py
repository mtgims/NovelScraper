"""POST /api/sync: send this device's library changes, get everyone else's."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlmodel import Session

from ..db import get_session
from ..models import User
from ..schemas import SyncRequest, SyncResponse
from ..services import sync as svc
from .deps import get_current_user

router = APIRouter()


@router.post("", response_model=SyncResponse)
def sync(body: SyncRequest, user: User = Depends(get_current_user),
         session: Session = Depends(get_session)):
    """Apply `changes` (last writer wins per record), then return the records
    written after `cursor`, including this call's own, which the device
    recognises and skips. Page with `more`."""
    if not body.device or len(body.device) > 64:
        raise HTTPException(status_code=422, detail="device id required")
    svc.seed_user(session, user)
    try:
        svc.apply_changes(session, user, body.device, body.changes)
    except svc.SyncError as e:
        raise HTTPException(status_code=422, detail=str(e))
    rows, cursor, more = svc.changes_since(session, user, body.cursor)
    return SyncResponse(cursor=cursor, changes=svc.as_changes(rows), more=more)
