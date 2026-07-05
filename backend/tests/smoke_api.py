"""Self-contained API smoke test.

Spins up a mock novel site in a background thread and drives the real ASGI app
through its full lifecycle (URL-based scrape -> library -> chapters -> reader ->
download -> cancel -> delete). Run with the project venv:

    cd backend && .venv/bin/python tests/smoke_api.py
"""

import os, threading, asyncio, time, tempfile, pathlib, sys

TMP = tempfile.mkdtemp(prefix="ns_api_")
PROFILES = pathlib.Path(TMP, "profiles"); PROFILES.mkdir()
(PROFILES / "mock.yaml").write_text("""
name: mock
base_url: http://127.0.0.1:8911
enumeration: paginated
list_url_template: "{base_url}/book/{book}/chapters?page={page}"
book_url_regex: "/book/(?P<book>[^/?#]+)"
list_container_selector: "ul.chapter-list"
link_selector: "a"
chapter_no_selector: "span.chapter-no"
content_selector: "#content"
""")
os.environ["NOVELSCRAPER_PROFILE_DIR"] = str(PROFILES)
os.environ["NOVELSCRAPER_DATA_DIR"] = str(pathlib.Path(TMP, "data"))
os.environ["NOVELSCRAPER_ALLOW_PRIVATE_HOSTS"] = "1"

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from aiohttp import web

def chapter_html(n):
    return f"<html><body><div id='content'><p>Body of {n}</p></div></body></html>"

async def chapters_page(req):
    page = int(req.query.get("page", "1"))
    if page > 2:
        return web.Response(text="<html><body>none</body></html>")
    items = "".join(
        f"<a href='/c/{i}' title='T{i}'><span class='chapter-no'>{i}</span></a>"
        for i in range((page-1)*3+1, (page-1)*3+4))
    return web.Response(text=f"<html><body><ul class='chapter-list'>{items}</ul></body></html>")

async def chapter(req):
    await asyncio.sleep(float(req.app["delay"]))
    return web.Response(text=chapter_html(req.match_info["n"]), content_type="text/html")

async def book_page(req):
    # Book landing page with OpenGraph/meta metadata for generic extraction.
    return web.Response(text=(
        "<html><head>"
        "<meta property='og:title' content='My Book Title'>"
        "<meta property='og:image' content='/cover.jpg'>"
        "<meta name='author' content='Jane Doe'>"
        "</head><body>book</body></html>"
    ), content_type="text/html")

async def cover(req):
    # Minimal valid-ish JPEG bytes (magic header is enough for our purposes).
    return web.Response(body=b"\xff\xd8\xff\xe0JFIFcoverdata", content_type="image/jpeg")

async def robots(req):
    return web.Response(text="User-agent: *\nAllow: /\n")

def run_server():
    loop = asyncio.new_event_loop(); asyncio.set_event_loop(loop)
    app = web.Application(); app["delay"] = 0.05
    app.router.add_get("/robots.txt", robots)
    app.router.add_get("/book/{book}/chapters", chapters_page)
    app.router.add_get("/book/{book}", book_page)
    app.router.add_get("/cover.jpg", cover)
    app.router.add_get("/c/{n}", chapter)
    runner = web.AppRunner(app); loop.run_until_complete(runner.setup())
    loop.run_until_complete(web.TCPSite(runner, "127.0.0.1", 8911).start())
    loop.run_forever()

threading.Thread(target=run_server, daemon=True).start()
time.sleep(0.7)

from fastapi.testclient import TestClient
from app.main import app

BASE = "http://127.0.0.1:8911"
results = []
def check(name, ok, extra=""):
    results.append(ok)
    print(f"[{name}] {'PASS' if ok else 'FAIL'} {extra}")

def poll(client, jid, want, timeout=30):
    end = time.time() + timeout
    st = {}
    while time.time() < end:
        st = client.get(f"/api/jobs/{jid}").json()
        if st["status"] in want:
            return st
        time.sleep(0.1)
    raise AssertionError(f"job stuck: {st}")

with TestClient(app) as client:
    check("health", client.get("/api/health").json() == {"status": "ok"})
    check("sites", any(s["name"] == "mock" for s in client.get("/api/sites").json()))

    # URL resolution errors
    check("unsupported-422",
          client.post("/api/jobs", json={"url": "http://example.com/book/x"}).status_code == 422)
    check("nobook-422",
          client.post("/api/jobs", json={"url": f"{BASE}/notabook"}).status_code == 422)
    check("missing-job", client.get("/api/jobs/deadbeef").status_code == 404)

    r = client.post("/api/jobs", json={"url": f"{BASE}/book/my-book", "chapters_per_volume": 4})
    check("create-202", r.status_code == 202, str(r.status_code))
    jid = r.json()["id"]
    check("source-url", r.json()["source_url"] == f"{BASE}/book/my-book")

    dup = client.post("/api/jobs", json={"url": f"{BASE}/book/my-book"})
    check("duplicate-409", dup.status_code == 409, str(dup.status_code))

    done = poll(client, jid, {"completed", "failed"})
    check("completed", done["status"] == "completed" and done["total_chapters"] == 6, str(done["status"]))

    books = client.get("/api/books").json()
    check("library", len(books) == 1 and len(books[0]["volumes"]) == 2)
    bid = books[0]["id"]
    b = books[0]
    check("metadata-title", b["title"] == "My Book Title", b["title"])
    check("metadata-author", b["author"] == "Jane Doe", b["author"])
    check("has-cover", b["has_cover"] is True)

    cov = client.get(f"/api/books/{bid}/cover")
    check("cover-served", cov.status_code == 200 and cov.content[:2] == b"\xff\xd8")

    zipdl = client.get(f"/api/books/{bid}/download-all")
    check("download-all", zipdl.status_code == 200 and zipdl.content[:2] == b"PK")

    toc = client.get(f"/api/books/{bid}/chapters").json()
    check("chapters-toc", len(toc) == 6 and toc[0]["position"] == 1)
    c1 = client.get(f"/api/books/{bid}/chapters/1").json()
    check("chapter-read", c1["has_prev"] is False and c1["has_next"] is True and "Body of" in c1["content"])

    dl = client.get(f"/api/books/{bid}/download", params={"volume": 1})
    check("download", dl.status_code == 200 and dl.content[:2] == b"PK")

    # reading progress: initial, update resume + mark-read, stats
    p0 = client.get(f"/api/books/{bid}/progress").json()
    check("progress-initial", p0["total_chapters"] == 6 and p0["read_count"] == 0
          and p0["total_words"] > 0)
    p1 = client.put(f"/api/books/{bid}/progress",
                    json={"last_position": 3, "scroll": 0.5, "mark_read": 1}).json()
    check("progress-update", p1["last_position"] == 3 and p1["read_count"] == 1
          and p1["read_positions"] == [1] and p1["percent_read"] > 0)
    p2 = client.put(f"/api/books/{bid}/progress", json={"mark_read": 2}).json()
    check("progress-mark2", p2["read_count"] == 2 and p2["chapters_left"] == 4)
    check("progress-persist", client.get(f"/api/books/{bid}/progress").json()["last_position"] == 3)

    # mark all read, per-chapter unmark, reset
    pa = client.put(f"/api/books/{bid}/progress", json={"mark_all": True}).json()
    check("mark-all", pa["read_count"] == 6 and pa["chapters_left"] == 0)
    pu = client.put(f"/api/books/{bid}/progress", json={"unmark_read": 3}).json()
    check("unmark-one", pu["read_count"] == 5 and 3 not in pu["read_positions"])
    pr = client.put(f"/api/books/{bid}/progress", json={"reset": True}).json()
    check("reset", pr["read_count"] == 0 and pr["last_position"] == 1)

    # re-mark two so stats below have data
    client.put(f"/api/books/{bid}/progress", json={"last_position": 3, "mark_read": 1})
    client.put(f"/api/books/{bid}/progress", json={"mark_read": 2})

    # TTS (optional): voices + manifest + a synthesized chunk (WAV)
    vinfo = client.get("/api/tts/voices").json()
    check("tts-voices", "available" in vinfo)
    if vinfo["available"]:
        man = client.get(f"/api/books/{bid}/chapters/1/audio/manifest").json()
        check("tts-manifest", isinstance(man["chunks"], list) and len(man["chunks"]) >= 1
              and len(man["paragraphs"]) >= 1)
        au = client.get(f"/api/books/{bid}/chapters/1/audio/0")
        ctype = au.headers.get("content-type", "")
        # Compressed to MP3 when ffmpeg is present; WAV fallback otherwise.
        check("tts-audio", au.status_code == 200 and len(au.content) > 0
              and ctype in ("audio/mpeg", "audio/wav"), ctype)
    else:
        print("[tts] unavailable (skipping synth checks)")

    # global statistics
    st = client.get("/api/stats").json()
    check("stats", st["total_books"] == 1 and st["chapters_read"] == 2
          and st["total_chapters"] == 6 and st["books_started"] == 1
          and len(st["books"]) == 1 and st["books"][0]["read_count"] == 2)

    # collections: create, assign a book, and confirm book serialization carries
    # the membership *and* the volumes (one consistent BookRead everywhere).
    cid = client.post("/api/collections", json={"name": "Faves"}).json()["id"]
    bc = client.put(f"/api/books/{bid}/collections", json={"collection_ids": [cid]}).json()
    check("collections-assign", bc["collection_ids"] == [cid] and len(bc["volumes"]) == 2,
          str(bc.get("collection_ids")))
    lib2 = client.get("/api/books").json()
    check("collections-in-list", lib2[0]["collection_ids"] == [cid])

    # mid-run cancel
    r2 = client.post("/api/jobs", json={"url": f"{BASE}/book/slow-book", "delay": 1.5, "concurrency": 1})
    jid2 = r2.json()["id"]
    poll(client, jid2, {"running"}, timeout=10); time.sleep(0.2)
    check("cancel-200", client.post(f"/api/jobs/{jid2}/cancel").status_code == 200)
    check("cancelled", poll(client, jid2, {"cancelled", "completed"}, 15)["status"] == "cancelled")

    # delete/clear finished job records
    check("delete-job-204", client.delete(f"/api/jobs/{jid2}").status_code == 204)
    cleared = client.delete("/api/jobs").json()
    check("clear-finished", cleared["deleted"] >= 1 and client.get("/api/jobs").json() == [])

    check("delete-204", client.delete(f"/api/books/{bid}").status_code == 204)
    check("library-empty", client.get("/api/books").json() == [])

print(f"\nSUMMARY: {sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
