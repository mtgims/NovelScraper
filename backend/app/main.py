"""FastAPI application entrypoint.

Run from the ``backend/`` directory:

    uvicorn app.main:app --reload
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import auth, books, collections, config, imports, jobs, sites, stats, tts
from .api.deps import get_current_user
from .db import engine, init_db
from .jobs.manager import JobManager
from .maintenance import cache_pruner_loop
from .scraper import load_profiles
from .settings import settings
from .updater import auto_update_loop

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    profiles = load_profiles(settings.profile_dir)
    logger.info("loaded %d site profile(s): %s", len(profiles), sorted(profiles))
    app.state.manager = JobManager(engine, profiles, settings)
    # Warm the TTS model in the background (download + load) so the first
    # "Listen" doesn't pay that cost. Never blocks startup; failures just leave
    # TTS reporting itself unavailable.
    from .tts import tts
    asyncio.create_task(asyncio.to_thread(tts.available))
    # Keep the regenerable disk caches (scrape HTML, TTS audio) under their size
    # caps so a long-running server can't fill its disk.
    pruner = asyncio.create_task(cache_pruner_loop())
    updater = asyncio.create_task(auto_update_loop(app.state.manager, engine))
    try:
        yield
    finally:
        pruner.cancel()
        updater.cancel()
        await app.state.manager.shutdown()


app = FastAPI(title="NovelScraper API", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Auth endpoints are public (login/register) or self-guarding (me/logout/admin).
app.include_router(auth.router, prefix="/api/auth", tags=["auth"])

# Every data router requires a valid session. Applying the dependency at the
# router level means no endpoint can be accidentally left unauthenticated;
# per-user data isolation is then enforced inside each endpoint.
authed = [Depends(get_current_user)]
app.include_router(jobs.router, prefix="/api/jobs", tags=["jobs"], dependencies=authed)
app.include_router(books.router, prefix="/api/books", tags=["books"], dependencies=authed)
app.include_router(imports.router, prefix="/api", tags=["import"], dependencies=authed)
app.include_router(collections.router, prefix="/api/collections", tags=["collections"], dependencies=authed)
app.include_router(config.router, prefix="/api/settings", tags=["settings"], dependencies=authed)
app.include_router(sites.router, prefix="/api/sites", tags=["sites"], dependencies=authed)
app.include_router(stats.router, prefix="/api/stats", tags=["stats"], dependencies=authed)
app.include_router(tts.router, prefix="/api/tts", tags=["tts"], dependencies=authed)


@app.get("/api/health")
def health():
    return {"status": "ok"}
