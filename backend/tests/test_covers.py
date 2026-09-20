"""Cover-serving tests: conditional requests, cache headers, thumbnails.

Pins the behaviour of GET /api/books/{id}/cover (and /images/{name}) so the
thumbnail + 304 work can't silently regress into re-sending megabytes. Run with
the project venv:

    cd backend && .venv/bin/python tests/test_covers.py
"""

import os, tempfile, pathlib, sys

TMP = tempfile.mkdtemp(prefix="ns_cover_")
DATA = pathlib.Path(TMP, "data")
os.environ["NOVELSCRAPER_DATA_DIR"] = str(DATA)
os.environ["NOVELSCRAPER_DB"] = str(pathlib.Path(TMP, "cover.db"))
PROFILES = pathlib.Path(TMP, "profiles"); PROFILES.mkdir()
os.environ["NOVELSCRAPER_PROFILE_DIR"] = str(PROFILES)
os.environ["NOVELSCRAPER_ADMIN_USERNAME"] = "admin"
os.environ["NOVELSCRAPER_ADMIN_PASSWORD"] = "cover-pw-123"

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from sqlmodel import Session
from app.main import app
from app.auth import COOKIE_NAME
from app.db import engine
from app.models import Book, User
from app.services import images

ok = []
def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(f"[{name}] {'PASS' if cond else 'FAIL'} {extra}")

def as_(tok):
    return {COOKIE_NAME: tok} if tok else {}

HAVE_PILLOW = images.Image is not None
if not HAVE_PILLOW:
    print("NOTE: Pillow absent — thumbnail assertions check the fallback path")

# A 1200px-wide source cover, far wider than any card renders it.
SRC_W, SRC_H = 1200, 1800
cover_path = DATA / "covers" / "wide-cover.png"
cover_path.parent.mkdir(parents=True, exist_ok=True)
if HAVE_PILLOW:
    from PIL import Image as PILImage
    # A smooth two-axis gradient: representative of real cover art, and — unlike
    # high-frequency noise — it actually demonstrates the byte saving.
    img = PILImage.new("RGB", (SRC_W, SRC_H))
    px = img.load()
    for y in range(SRC_H):
        gy = y * 255 // SRC_H
        for x in range(SRC_W):
            gx = x * 255 // SRC_W
            px[x, y] = (gx, gy, (gx + gy) // 2)
    img.save(cover_path, format="PNG")
else:
    cover_path.write_bytes(b"\x89PNG\r\n\x1a\n" + b"not-a-real-image" * 64)
SRC_BYTES = cover_path.stat().st_size

with TestClient(app) as c:
    c.cookies.clear()
    r = c.post("/api/auth/login", json={"username": "admin", "password": "cover-pw-123"})
    tok = c.cookies.get(COOKIE_NAME)
    c.cookies.clear()
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        admin_id = s.query(User).first().id
        book = Book(slug="cover-book", site="mock", title="Cover Book",
                    cover_path=str(cover_path), user_id=admin_id)
        s.add(book); s.commit(); s.refresh(book)
        book_id = book.id

    # --- the original is still served unchanged ---
    full = c.get(f"/api/books/{book_id}/cover", cookies=as_(tok))
    check("cover-200", full.status_code == 200, str(full.status_code))
    check("cover-is-source-bytes", len(full.content) == SRC_BYTES,
          f"{len(full.content)} vs {SRC_BYTES}")
    check("cover-has-etag", full.headers.get("etag", "").startswith('"'))
    check("cover-has-cache-control",
          "max-age" in full.headers.get("cache-control", ""),
          full.headers.get("cache-control", ""))

    # --- a conditional request is answered 304 with no body (the regression
    #     this work fixed: FileResponse emitted an ETag but ignored it) ---
    etag = full.headers["etag"]
    cond = c.get(f"/api/books/{book_id}/cover", cookies=as_(tok),
                 headers={"If-None-Match": etag})
    check("cover-conditional-304", cond.status_code == 304, str(cond.status_code))
    check("cover-304-empty", len(cond.content) == 0, str(len(cond.content)))
    weak = c.get(f"/api/books/{book_id}/cover", cookies=as_(tok),
                 headers={"If-None-Match": f"W/{etag}"})
    check("cover-weak-etag-304", weak.status_code == 304, str(weak.status_code))
    stale = c.get(f"/api/books/{book_id}/cover", cookies=as_(tok),
                  headers={"If-None-Match": '"not-the-etag"'})
    check("cover-stale-etag-200", stale.status_code == 200, str(stale.status_code))
    ims = c.get(f"/api/books/{book_id}/cover", cookies=as_(tok),
                headers={"If-Modified-Since": full.headers["last-modified"]})
    check("cover-if-modified-since-304", ims.status_code == 304, str(ims.status_code))

    # --- ?w= renders a width-capped WebP, materially smaller than the source ---
    thumb = c.get(f"/api/books/{book_id}/cover?w=400", cookies=as_(tok))
    check("thumb-200", thumb.status_code == 200, str(thumb.status_code))
    if HAVE_PILLOW:
        check("thumb-is-webp", thumb.headers["content-type"] == "image/webp",
              thumb.headers.get("content-type", ""))
        check("thumb-smaller-than-source", len(thumb.content) < SRC_BYTES // 2,
              f"{len(thumb.content)}B vs {SRC_BYTES}B source")
        from PIL import Image as PILImage
        import io
        check("thumb-width-is-400",
              PILImage.open(io.BytesIO(thumb.content)).width == 400)
        # Second request must hit the on-disk cache, not re-render.
        again = c.get(f"/api/books/{book_id}/cover?w=400", cookies=as_(tok))
        check("thumb-cached-identical", again.content == thumb.content)
        check("thumb-conditional-304",
              c.get(f"/api/books/{book_id}/cover?w=400", cookies=as_(tok),
                    headers={"If-None-Match": thumb.headers["etag"]}).status_code == 304)
    else:
        check("thumb-falls-back-to-source", len(thumb.content) == SRC_BYTES)

    # --- a width outside the allowlist falls back to the original ---
    odd = c.get(f"/api/books/{book_id}/cover?w=123", cookies=as_(tok))
    check("unknown-width-serves-original", len(odd.content) == SRC_BYTES,
          f"{len(odd.content)} vs {SRC_BYTES}")

    # --- a source narrower than the requested width is not upscaled ---
    small_path = DATA / "covers" / "small-cover.png"
    if HAVE_PILLOW:
        from PIL import Image as PILImage
        PILImage.new("RGB", (120, 180), (30, 60, 90)).save(small_path, format="PNG")
        with Session(engine) as s:
            b2 = Book(slug="small", site="mock", title="Small",
                      cover_path=str(small_path), user_id=admin_id)
            s.add(b2); s.commit(); s.refresh(b2)
            small_id = b2.id
        small = c.get(f"/api/books/{small_id}/cover?w=400", cookies=as_(tok))
        small_src = small_path.stat().st_size
        check("no-upscale-never-more-bytes", len(small.content) <= small_src,
              f"{len(small.content)} vs {small_src}")
        if small.headers.get("content-type") == "image/webp":
            import io as _io
            from PIL import Image as _PI
            check("no-upscale-width-capped-at-source",
                  _PI.open(_io.BytesIO(small.content)).width == 120)
        else:
            check("no-upscale-width-capped-at-source", True, "served original")

    # --- ownership is still enforced on every variant ---
    check("cover-unauth-401",
          c.get(f"/api/books/{book_id}/cover").status_code == 401)
    check("thumb-unauth-401",
          c.get(f"/api/books/{book_id}/cover?w=400").status_code == 401)
    check("cover-missing-book-404",
          c.get("/api/books/999999/cover", cookies=as_(tok)).status_code == 404)

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
