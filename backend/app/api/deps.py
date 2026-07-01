"""Shared FastAPI dependencies."""

from __future__ import annotations

from fastapi import Request

from ..jobs.manager import JobManager


def get_manager(request: Request) -> JobManager:
    return request.app.state.manager
