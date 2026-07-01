"""Site profile endpoints (read-only for now)."""

from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends

from ..schemas import SiteRead
from ..jobs.manager import JobManager
from .deps import get_manager

router = APIRouter()


@router.get("", response_model=List[SiteRead])
def list_sites(manager: JobManager = Depends(get_manager)):
    return [
        SiteRead(name=p.name, base_url=p.base_url, enumeration=p.enumeration)
        for p in manager.profiles.values()
    ]
