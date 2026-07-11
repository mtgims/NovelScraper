"""Editable app settings (persisted). Currently just the auto-update interval."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ..models import User
from ..schemas import AppSettings, AppSettingsUpdate
from ..store import (
    get_allow_open_signup,
    get_auto_update_hours,
    set_allow_open_signup,
    set_auto_update_hours,
)
from .deps import get_admin

router = APIRouter()


def _current() -> AppSettings:
    return AppSettings(
        auto_update_hours=get_auto_update_hours(),
        allow_open_signup=get_allow_open_signup(),
    )


@router.get("", response_model=AppSettings)
def read_settings():
    # Global app settings; any authenticated user may read them (router-level auth).
    return _current()


@router.put("", response_model=AppSettings)
def write_settings(body: AppSettingsUpdate, admin: User = Depends(get_admin)):
    # Writing global settings is an admin action.
    if body.auto_update_hours is not None:
        set_auto_update_hours(body.auto_update_hours)
    if body.allow_open_signup is not None:
        set_allow_open_signup(body.allow_open_signup)
    return _current()
