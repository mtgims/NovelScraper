"""Editable app settings (persisted). Currently just the auto-update interval."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ..models import User
from ..schemas import AppSettings, AppSettingsUpdate
from ..store import get_auto_update_hours, set_auto_update_hours
from .deps import get_admin

router = APIRouter()


@router.get("", response_model=AppSettings)
def read_settings():
    # Global app setting; any authenticated user may read it (router-level auth).
    return AppSettings(auto_update_hours=get_auto_update_hours())


@router.put("", response_model=AppSettings)
def write_settings(body: AppSettingsUpdate, admin: User = Depends(get_admin)):
    # Writing the global auto-update interval is an admin action.
    if body.auto_update_hours is not None:
        set_auto_update_hours(body.auto_update_hours)
    return AppSettings(auto_update_hours=get_auto_update_hours())
