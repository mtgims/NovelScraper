"""Library metadata sync (POST /api/sync): seeding from the server's tables,
last-writer-wins per record, cursors and paging, mirroring into the server's own
tables, deletes reaching other devices, and isolation between users. Run with
the project venv:

    cd backend && .venv/bin/python tests/test_sync.py
"""

import json, os, tempfile, pathlib, sys

TMP = tempfile.mkdtemp(prefix="ns_sync_")
os.environ["NOVELSCRAPER_DATA_DIR"] = str(pathlib.Path(TMP, "data"))
os.environ["NOVELSCRAPER_DB"] = str(pathlib.Path(TMP, "sync.db"))
PROFILES = pathlib.Path(TMP, "profiles"); PROFILES.mkdir()
os.environ["NOVELSCRAPER_PROFILE_DIR"] = str(PROFILES)
os.environ["NOVELSCRAPER_ADMIN_USERNAME"] = "admin"
os.environ["NOVELSCRAPER_ADMIN_PASSWORD"] = "admin-pw-123"

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from ebooklib import epub
from app.main import app
from app.auth import COOKIE_NAME

ok = []
def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(f"[{name}] {'PASS' if cond else 'FAIL'} {extra}")

def as_(tok):
    return {COOKIE_NAME: tok} if tok else {}

def make_epub(title, chapters=3):
    b = epub.EpubBook()
    b.set_identifier(title); b.set_title(title); b.set_language("en"); b.add_author("A")
    items = []
    for i in range(1, chapters + 1):
        c = epub.EpubHtml(title=f"Ch{i}", file_name=f"c{i}.xhtml", lang="en")
        c.content = f"<h1>Chapter {i}</h1><p>Body text of chapter {i}.</p>"
        b.add_item(c); items.append(c)
    b.toc = tuple(items); b.add_item(epub.EpubNcx()); b.add_item(epub.EpubNav())
    b.spine = ["nav", *items]
    p = pathlib.Path(TMP, f"{title}.epub"); epub.write_epub(str(p), b)
    return p.read_bytes()

def change(kind, key, value, ts, device="dev-a"):
    return {"kind": kind, "key": key, "value": json.dumps(value), "ts": ts, "device": device}

def sync(c, tok, device, cursor=0, changes=()):
    return c.post("/api/sync", cookies=as_(tok),
                  json={"cursor": cursor, "device": device, "changes": list(changes)})

def by_key(changes):
    return {(x["kind"], x["key"]): x for x in changes}

with TestClient(app) as c:
    c.cookies.clear()
    c.post("/api/auth/login", json={"username": "admin", "password": "admin-pw-123"})
    admin = c.cookies.get(COOKIE_NAME); c.cookies.clear()
    code = c.post("/api/auth/invites", cookies=as_(admin)).json()["code"]
    c.post("/api/auth/register", json={"username": "bob", "password": "password123", "invite_code": code})
    bob = c.cookies.get(COOKIE_NAME); c.cookies.clear()

    # The admin's library before sync: a novel with a rating, progress, a shelf.
    book = c.post("/api/import", cookies=as_(admin),
                  files={"files": ("Alpha.epub", make_epub("Alpha"), "application/epub+zip")}).json()
    bid = book["id"]; nk = f"srv:{bid}"
    c.patch(f"/api/books/{bid}", cookies=as_(admin), json={"rating": 4})
    c.put(f"/api/books/{bid}/progress", cookies=as_(admin), json={"last_position": 2, "mark_positions": [1, 2]})
    col = c.post("/api/collections", cookies=as_(admin), json={"name": "Faves"}).json()
    c.put(f"/api/books/{bid}/collections", cookies=as_(admin), json={"collection_ids": [col["id"]]})

    # 1. The first sync seeds records from those tables, at the lowest clock.
    r = sync(c, admin, "dev-a")
    check("first sync ok", r.status_code == 200, r.text[:200])
    first = r.json(); got = by_key(first["changes"])
    check("seeded novel", ("novel", nk) in got and json.loads(got[("novel", nk)]["value"])["in_library"])
    check("seeded rating", json.loads(got[("rating", nk)]["value"]) == {"rating": 4})
    check("seeded progress", json.loads(got[("progress", nk)]["value"])["chapter"] == "2")
    check("seeded read marks", ("read", f"{nk}\t1") in got and ("read", f"{nk}\t2") in got
          and ("read", f"{nk}\t3") not in got)
    check("seeded collection", json.loads(got[("collection", f"srv:{col['id']}")]["value"])["name"] == "Faves")
    check("seeded shelf", ("shelf", f"srv:{col['id']}\t{nk}") in got)
    check("seed clock is lowest", all(x["ts"] == 1 for x in first["changes"]))
    cursor_a = first["cursor"]
    again = sync(c, admin, "dev-a", cursor_a).json()
    check("seeding happens once", again["changes"] == [], str(again["changes"])[:100])

    # 2. A device's change wins over the seed and reaches the server's tables.
    r = sync(c, admin, "dev-a", cursor_a, [change("rating", nk, {"rating": 2}, 1000),
                                            change("read", f"{nk}\t3", {"read": True}, 1000),
                                            change("progress", nk, {"chapter": "3", "scroll": 0.5, "sentence": 7}, 1000)])
    resp = r.json()
    check("own changes come back (echo)", len(resp["changes"]) == 3)
    cursor_a = resp["cursor"]
    b = c.get(f"/api/books/{bid}", cookies=as_(admin)).json()
    check("rating mirrored", b["rating"] == 2, str(b["rating"]))
    p = c.get(f"/api/books/{bid}/progress", cookies=as_(admin)).json()
    check("read mark mirrored", p["read_positions"] == [1, 2, 3], str(p["read_positions"]))
    check("progress mirrored", p["last_position"] == 3 and abs(p["scroll"] - 0.5) < 1e-6)

    # 3. Last writer wins: an older write from another device is refused...
    r = sync(c, admin, "dev-b", 0, [change("rating", nk, {"rating": 5}, 900, "dev-b")]).json()
    rating_rec = by_key(r["changes"])[("rating", nk)]
    check("older write refused", json.loads(rating_rec["value"]) == {"rating": 2} and rating_rec["device"] == "dev-a")
    # ...a same-time write breaks the tie by device id...
    sync(c, admin, "dev-b", 0, [change("rating", nk, {"rating": 3}, 1000, "dev-b")])
    r = sync(c, admin, "dev-a", cursor_a).json()
    got = by_key(r["changes"])
    check("tie goes to the higher device id", json.loads(got[("rating", nk)]["value"]) == {"rating": 3})
    cursor_a = r["cursor"]
    # ...and a newer one wins, unmarking a chapter.
    sync(c, admin, "dev-b", 0, [change("read", f"{nk}\t1", {"read": False}, 2000, "dev-b")])
    p = c.get(f"/api/books/{bid}/progress", cookies=as_(admin)).json()
    check("unmark mirrored", p["read_positions"] == [2, 3], str(p["read_positions"]))

    # 4. The cursor returns only what changed since.
    r = sync(c, admin, "dev-a", cursor_a).json()
    check("cursor: only the new write", [(x["kind"], x["key"]) for x in r["changes"]] == [("read", f"{nk}\t1")])
    cursor_a = r["cursor"]

    # 5. Source novels and new collections just sync (nothing to mirror).
    src = "src:novelscraper.royalroad\tfiction/123"
    sync(c, admin, "dev-b", 0, [
        change("novel", src, {"plugin": "novelscraper.royalroad", "path": "fiction/123", "server_id": None,
                              "title": "Src", "author": "", "cover": None, "site": "Royal Road",
                              "in_library": True}, 3000, "dev-b"),
        change("collection", "c-9f2", {"name": "Later", "sort": 5, "deleted": False}, 3000, "dev-b"),
        change("shelf", f"c-9f2\t{src}", {"member": True}, 3000, "dev-b"),
    ])
    r = sync(c, admin, "dev-a", cursor_a).json()
    check("source novel reaches the other device", ("novel", src) in by_key(r["changes"]))
    cursor_a = r["cursor"]
    check("server collection unchanged", [x["name"] for x in c.get("/api/collections", cookies=as_(admin)).json()] == ["Faves"])

    # 6. Collections and shelves of server novels are mirrored.
    sync(c, admin, "dev-b", 0, [change("shelf", f"srv:{col['id']}\t{nk}", {"member": False}, 4000, "dev-b"),
                               change("collection", f"srv:{col['id']}", {"name": "Best", "sort": 0, "deleted": False}, 4000, "dev-b")])
    b = c.get(f"/api/books/{bid}", cookies=as_(admin)).json()
    check("shelf removal mirrored", b["collection_ids"] == [], str(b["collection_ids"]))
    check("rename mirrored", [x["name"] for x in c.get("/api/collections", cookies=as_(admin)).json()] == ["Best"])

    # 7. Bad input is refused whole.
    r = sync(c, admin, "dev-a", 0, [change("secrets", "x", {}, 1)])
    check("unknown kind refused", r.status_code == 422)
    r = c.post("/api/sync", cookies=as_(admin), json={"cursor": 0, "device": "dev-a",
               "changes": [{"kind": "rating", "key": nk, "value": "{not json", "ts": 5}]})
    check("non-JSON value refused", r.status_code == 422)
    r = sync(c, admin, "dev-a", 0, [change("read", f"{nk}\t{i}", {"read": True}, 5000) for i in range(2001)])
    check("too many changes refused", r.status_code == 422)
    c.cookies.clear()
    check("unauthenticated refused", c.post("/api/sync", json={"cursor": 0, "device": "x", "changes": []}).status_code == 401)

    # 8. Paging: a large backlog comes in pages.
    many = [change("read", f"{src}\tch{i}", {"read": True}, 6000) for i in range(2500)]
    for i in range(0, 2500, 1000):
        sync(c, admin, "dev-b", 0, many[i:i + 1000])
    total, cur, pages = 0, cursor_a, 0
    while True:
        r = sync(c, admin, "dev-a", cur).json()
        total += len(r["changes"]); cur = r["cursor"]; pages += 1
        if not r["more"]:
            break
    check("paging: every change, in two pages", total >= 2500 and pages == 2, f"{total} in {pages}")

    # 9. Isolation: bob sees nothing of the admin's and can't touch the admin's novel.
    r = sync(c, bob, "bob-1").json()
    check("bob starts empty", r["changes"] == [], str(r["changes"])[:100])
    sync(c, bob, "bob-1", 0, [change("rating", nk, {"rating": 1}, 9_999_999, "bob-1")])
    b = c.get(f"/api/books/{bid}", cookies=as_(admin)).json()
    check("bob's record doesn't reach the admin's novel", b["rating"] == 3, str(b["rating"]))
    r = sync(c, admin, "dev-a", cur).json()
    check("bob's record isn't the admin's", all(x["device"] != "bob-1" for x in r["changes"]))
    cur = r["cursor"]

    # 10. Deleting a server novel takes it out of the other devices' libraries.
    check("delete", c.delete(f"/api/books/{bid}", cookies=as_(admin)).status_code == 204)
    r = sync(c, admin, "dev-a", cur).json()
    novel = by_key(r["changes"]).get(("novel", nk))
    check("delete reaches devices", novel is not None and json.loads(novel["value"])["in_library"] is False
          and novel["device"] == "server")

print(f"\n{sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
