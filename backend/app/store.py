"""Persisted key/value app settings, editable from the UI. Falls back to the
env-configured default (app.settings) when a value hasn't been set."""

from __future__ import annotations

from sqlmodel import Session

from .db import engine
from .models import Setting
from .settings import settings

AUTO_UPDATE_HOURS = "auto_update_hours"
ALLOW_OPEN_SIGNUP = "allow_open_signup"


def get_allow_open_signup() -> bool:
    """Whether registration is open to anyone (no invite). Persisted override of
    the NOVELSCRAPER_ALLOW_OPEN_SIGNUP env default, so an admin can flip it from
    the UI without a redeploy."""
    with Session(engine) as s:
        row = s.get(Setting, ALLOW_OPEN_SIGNUP)
    if row is None:
        return settings.allow_open_signup  # env default
    return row.value == "1"


def set_allow_open_signup(enabled: bool) -> None:
    with Session(engine) as s:
        row = s.get(Setting, ALLOW_OPEN_SIGNUP)
        val = "1" if enabled else "0"
        if row is None:
            s.add(Setting(key=ALLOW_OPEN_SIGNUP, value=val))
        else:
            row.value = val
            s.add(row)
        s.commit()


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
