"""Job endpoints: submit, query, cancel, and a live SSE progress stream."""

from __future__ import annotations

import asyncio
import json
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlmodel import Session, select
from sse_starlette.sse import EventSourceResponse

from ..db import get_session
from ..jobs.manager import ACTIVE, TERMINAL, DuplicateJobError, JobManager
from ..scraper.errors import ScraperError
from ..models import Job
from ..schemas import JobCreate, JobRead
from .deps import get_manager

router = APIRouter()

# Seconds between keepalive pings when no events arrive, so proxies don't drop
# an idle SSE connection.
SSE_PING_INTERVAL = 15


@router.post("", response_model=JobRead, status_code=status.HTTP_202_ACCEPTED)
async def create_job(data: JobCreate, manager: JobManager = Depends(get_manager)):
    # async so manager.submit() can schedule the worker task on the event loop.
    try:
        return manager.submit(data)
    except DuplicateJobError:
        raise HTTPException(
            status_code=409,
            detail="A scrape for this book is already queued or running.",
        )
    except ScraperError as e:
        # UnsupportedSourceError / ProfileError — the URL isn't a supported source.
        raise HTTPException(status_code=422, detail=str(e))


@router.get("", response_model=List[JobRead])
def list_jobs(
    status_filter: Optional[str] = Query(default=None, alias="status"),
    session: Session = Depends(get_session),
):
    query = select(Job).order_by(Job.created_at.desc())
    if status_filter:
        query = query.where(Job.status == status_filter)
    return session.exec(query).all()


@router.delete("", status_code=200)
def clear_finished_jobs(session: Session = Depends(get_session)):
    """Delete all finished (completed/failed/cancelled) job records. Active jobs
    are kept. Does not touch scraped books."""
    jobs = session.exec(select(Job).where(Job.status.in_(TERMINAL))).all()
    for job in jobs:
        session.delete(job)
    session.commit()
    return {"deleted": len(jobs)}


@router.delete("/{job_id}", status_code=204)
def delete_job(job_id: str, session: Session = Depends(get_session)):
    job = session.get(Job, job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")
    if job.status in ACTIVE:
        raise HTTPException(status_code=409, detail="Cancel the job before deleting it")
    session.delete(job)
    session.commit()


@router.get("/{job_id}")
def get_job(job_id: str, manager: JobManager = Depends(get_manager)):
    snap = manager.snapshot(job_id)
    if snap is None:
        raise HTTPException(status_code=404, detail="Job not found")
    return snap


@router.post("/{job_id}/cancel")
async def cancel_job(job_id: str, manager: JobManager = Depends(get_manager)):
    # async so task.cancel() runs on the event loop thread (it isn't thread-safe).
    snap = manager.snapshot(job_id)
    if snap is None:
        raise HTTPException(status_code=404, detail="Job not found")
    cancelled = manager.cancel(job_id)
    if not cancelled:
        raise HTTPException(status_code=409, detail="Job is not running")
    return {"cancelled": True}


@router.get("/{job_id}/events")
async def job_events(job_id: str, request: Request,
                     manager: JobManager = Depends(get_manager)):
    snap = manager.snapshot(job_id)
    if snap is None:
        raise HTTPException(status_code=404, detail="Job not found")

    async def event_stream():
        queue = manager.bus.subscribe(job_id)
        try:
            # Initial snapshot so a late subscriber sees current state immediately.
            yield {"event": "snapshot", "data": json.dumps(snap)}
            # Re-check *after* subscribing: closes the race where the job reached
            # a terminal state between the snapshot above and the subscribe, which
            # would otherwise leave us waiting for an event that already fired.
            fresh = manager.snapshot(job_id)
            if fresh is None or fresh["status"] in TERMINAL:
                return
            while True:
                if await request.is_disconnected():
                    break
                try:
                    event = await asyncio.wait_for(queue.get(), SSE_PING_INTERVAL)
                except asyncio.TimeoutError:
                    yield {"event": "ping", "data": "{}"}
                    continue
                yield {"event": event.get("event", "progress"),
                       "data": json.dumps(event)}
                if event.get("event") in TERMINAL:
                    break
        finally:
            manager.bus.unsubscribe(job_id, queue)

    return EventSourceResponse(event_stream())
