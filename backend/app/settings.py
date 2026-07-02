"""Application settings for the API layer.

Paths and a few knobs are read from the environment so deployments and tests can
override them without code changes. Defaults target a local ``backend/data`` dir.
"""

from __future__ import annotations

import os
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent  # .../backend


def _env_bool(name: str, default: bool) -> bool:
    val = os.getenv(name)
    if val is None:
        return default
    return val.strip().lower() in ("1", "true", "yes", "on")


class Settings:
    def __init__(self) -> None:
        self.profile_dir = Path(
            os.getenv("NOVELSCRAPER_PROFILE_DIR", BACKEND_DIR / "site_profiles"))
        self.data_dir = Path(
            os.getenv("NOVELSCRAPER_DATA_DIR", BACKEND_DIR / "data"))
        self.db_path = Path(
            os.getenv("NOVELSCRAPER_DB", self.data_dir / "novelscraper.db"))
        self.output_dir = self.data_dir / "output"
        self.cache_dir = self.data_dir / "cache"
        self.cover_dir = self.data_dir / "covers"
        self.audio_dir = self.data_dir / "audio"          # cached TTS audio
        self.model_dir = Path(
            os.getenv("NOVELSCRAPER_MODEL_DIR", BACKEND_DIR / "models"))

        # TTS hardware selection: "auto" (GPU if available, else CPU), "cuda",
        # or "cpu". GPU (CUDA) uses the fp32 model and is ~20-40x faster than the
        # CPU int8 model. Requires onnxruntime-gpu + CUDA/cuDNN runtime libs.
        self.tts_device = os.getenv("NOVELSCRAPER_TTS_DEVICE", "auto").strip().lower()
        # Optional intra-op thread count for CPU synthesis (0 = let onnxruntime decide).
        self.tts_threads = int(os.getenv("NOVELSCRAPER_TTS_THREADS", "0"))

        # Disk-cache ceilings. Both caches are regenerable (HTML is re-fetched,
        # audio re-synthesized), so they're pruned LRU-style back under these caps
        # at startup and hourly — bounding total disk use so a long-running server
        # can't fill its disk. 0 disables pruning for that cache.
        self.max_cache_mb = int(os.getenv("NOVELSCRAPER_MAX_CACHE_MB", "500"))      # scrape HTML
        self.max_audio_mb = int(os.getenv("NOVELSCRAPER_MAX_AUDIO_MB", "1024"))     # TTS WAVs

        # Max scrapes running at once (each scrape is itself internally concurrent).
        self.max_concurrent_jobs = int(os.getenv("NOVELSCRAPER_MAX_JOBS", "2"))

        # Auto-update: how often (hours) to re-scrape books for new chapters.
        # 0 disables the scheduler; the manual "Update" button always works.
        self.auto_update_hours = int(os.getenv("NOVELSCRAPER_AUTO_UPDATE_HOURS", "0"))

        # SSRF guard escape hatch — only for local testing against 127.0.0.1.
        self.allow_private_hosts = _env_bool("NOVELSCRAPER_ALLOW_PRIVATE_HOSTS", False)

        origins = os.getenv("NOVELSCRAPER_CORS_ORIGINS")
        self.cors_origins = (
            [o.strip() for o in origins.split(",") if o.strip()]
            if origins else
            ["http://localhost:3000", "http://127.0.0.1:3000"]
        )

    def ensure_dirs(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.cover_dir.mkdir(parents=True, exist_ok=True)
        self.audio_dir.mkdir(parents=True, exist_ok=True)


settings = Settings()
