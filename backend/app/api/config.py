"""Editable app settings (persisted). Currently just the auto-update interval."""

from __future__ import annotations

from fastapi import APIRouter

from ..schemas import AppSettings, AppSettingsUpdate
from ..store import get_auto_update_hours, set_auto_update_hours

router = APIRouter()


@router.get("", response_model=AppSettings)
def read_settings():
    return AppSettings(auto_update_hours=get_auto_update_hours())


@router.put("", response_model=AppSettings)
def write_settings(body: AppSettingsUpdate):
    if body.auto_update_hours is not None:
        set_auto_update_hours(body.auto_update_hours)
    return AppSettings(auto_update_hours=get_auto_update_hours())
