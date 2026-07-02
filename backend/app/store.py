"""Persisted key/value app settings, editable from the UI. Falls back to the
env-configured default (app.settings) when a value hasn't been set."""

from __future__ import annotations

from sqlmodel import Session

from .db import engine
from .models import Setting
from .settings import settings

AUTO_UPDATE_HOURS = "auto_update_hours"


def get_auto_update_hours() -> int:
    with Session(engine) as s:
        row = s.get(Setting, AUTO_UPDATE_HOURS)
    if row is None:
        return settings.auto_update_hours  # env default
    try:
        return max(0, int(row.value))
    except ValueError:
        return settings.auto_update_hours


def set_auto_update_hours(hours: int) -> None:
    hours = max(0, int(hours))
    with Session(engine) as s:
        row = s.get(Setting, AUTO_UPDATE_HOURS)
        if row is None:
            s.add(Setting(key=AUTO_UPDATE_HOURS, value=str(hours)))
        else:
            row.value = str(hours)
            s.add(row)
        s.commit()
