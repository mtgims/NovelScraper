"""Cover image serving: conditional requests, cache headers, and thumbnails.

Two problems this solves, both measured on the library page (9 books):

1. ``FileResponse`` emits ``ETag``/``Last-Modified`` but never *honours* a
   conditional request, so a revalidating client re-downloaded the full image
   every time. :func:`not_modified` implements the RFC 9110 precondition check
   so a repeat visit costs a 304 instead of megabytes.

2. Covers are stored at whatever resolution the source site published (observed:
   1.4 MB and 786 kB JPEGs) and then rendered into a ~200 px card. :func:`thumb`
   returns a width-capped WebP rendition, generated once and cached on disk.

Thumbnailing needs Pillow. If it isn't installed the original file is served —
exactly the previous behaviour — so the feature degrades instead of breaking.
"""

from __future__ import annotations

import hashlib
import io
import logging
import os
import tempfile
from email.utils import formatdate, parsedate_to_datetime
from pathlib import Path
from typing import Optional

from fastapi import Request, Response
from fastapi.responses import FileResponse

logger = logging.getLogger(__name__)

try:  # optional: absent in a minimal install, and that's fine
    from PIL import Image
except Exception:  # noqa: BLE001 — any import failure means "no thumbnails"
    Image = None  # type: ignore[assignment]

# Widths the API will render. An allowlist (rather than a free-form integer)
# bounds how many renditions a caller can make us generate and cache.
THUMB_WIDTHS = (200, 400, 800)

# Covers change only when a book is re-scraped, but they are not content-addressed,
# so this stays short enough that a new cover appears promptly while still
# collapsing the burst of requests a single library render produces.
CACHE_CONTROL = "private, max-age=300"

_WEBP_QUALITY = 82


def file_validators(path: Path) -> tuple[str, str]:
    """``(etag, last_modified)`` for a file.

    Deliberately byte-identical to Starlette's ``FileResponse.set_stat_headers``
    so a client holding an ETag from before this endpoint handled conditional
    requests still validates on its first revalidation instead of re-downloading.
    """
    stat = os.stat(path)
    etag_base = f"{stat.st_mtime}-{stat.st_size}"
    etag = f'"{hashlib.md5(etag_base.encode(), usedforsecurity=False).hexdigest()}"'
    return etag, formatdate(stat.st_mtime, usegmt=True)


def not_modified(request: Request, etag: str, last_modified: str) -> bool:
    """True when the client's cached copy is still current (RFC 9110 §13.1).

    ``If-None-Match`` wins outright when present; ``If-Modified-Since`` is only
    consulted in its absence.
    """
    inm = request.headers.get("if-none-match")
    if inm:
        # A list of candidates, possibly weak ("W/") — compare weakly.
        candidates = [t.strip() for t in inm.split(",")]
        return "*" in candidates or any(
            t.removeprefix("W/") == etag for t in candidates
        )
    ims = request.headers.get("if-modified-since")
    if ims:
        try:
            return parsedate_to_datetime(ims) >= parsedate_to_datetime(last_modified)
        except (TypeError, ValueError):
            return False
    return False


def thumb_path(cover: Path, thumb_dir: Path, width: int) -> Path:
    """Where the width-``width`` rendition of ``cover`` is cached.

    Keyed by the source's mtime_ns and size as well as its name, so replacing a
    cover (re-scrape) yields a different path instead of serving the old image.
    """
    stat = os.stat(cover)
    return thumb_dir / f"{cover.stem}-{stat.st_mtime_ns:x}-{stat.st_size:x}-{width}.webp"


def _write_atomic(path: Path, data: bytes) -> None:
    """Temp file + ``os.replace`` so a concurrent reader never sees a partial
    image (two library renders can race to generate the same thumbnail)."""
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def ensure_thumb(cover: Path, thumb_dir: Path, width: int) -> Optional[Path]:
    """Path to a cached WebP rendition of ``cover`` at most ``width`` px wide,
    generating it if needed.

    Never upscales: the output is ``min(width, source width)`` px, so asking for
    400 from a 400 px source still re-encodes (some sources are badly compressed
    — one 400 px cover in the fixture library is a 165 kB JPEG) but never
    stretches.

    Returns None whenever the original should be served instead: no Pillow, an
    unreadable image, or a rendition that came out no smaller than the source.
    That last check means this can only ever reduce bytes on the wire.
    """
    if Image is None:
        return None
    out = thumb_path(cover, thumb_dir, width)
    if out.exists():
        return out
    try:
        source_bytes = os.path.getsize(cover)
        thumb_dir.mkdir(parents=True, exist_ok=True)
        with Image.open(cover) as im:
            target = min(width, im.width)
            im = im.convert("RGB")
            if target < im.width:
                height = round(im.height * target / im.width)
                im = im.resize((target, height), Image.LANCZOS)
            buf = io.BytesIO()
            im.save(buf, format="WEBP", quality=_WEBP_QUALITY, method=4)
            data = buf.getvalue()
    except Exception:  # noqa: BLE001 — a bad image must not 500 the library
        logger.warning("thumbnail generation failed for %s", cover, exc_info=True)
        return None
    if len(data) >= source_bytes:
        return None  # re-encoding did not help; serve the original
    _write_atomic(out, data)
    return out


def image_response(request: Request, path: Path, media_type: Optional[str] = None
                   ) -> Response:
    """Serve ``path`` with cache headers, answering a conditional request with
    304 instead of the body."""
    etag, last_modified = file_validators(path)
    headers = {"Cache-Control": CACHE_CONTROL}
    if not_modified(request, etag, last_modified):
        return Response(status_code=304, headers={
            **headers, "ETag": etag, "Last-Modified": last_modified,
        })
    return FileResponse(
        path,
        media_type=media_type,
        headers={**headers, "ETag": etag, "Last-Modified": last_modified},
    )
