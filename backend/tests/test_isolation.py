"""Cross-user isolation test: user B must never see or touch user A's data.

The whole point of the multi-user work — one buggy unscoped query here is a data
leak. Builds a real imported book + a collection for an admin, then asserts a
second user (bob) is walled off from every book/collection resource, while still
being able to use his own. Run with the project venv:

    cd backend && .venv/bin/python tests/test_isolation.py
"""

import io, os, tempfile, pathlib, sys

TMP = tempfile.mkdtemp(prefix="ns_iso_")
os.environ["NOVELSCRAPER_DATA_DIR"] = str(pathlib.Path(TMP, "data"))
os.environ["NOVELSCRAPER_DB"] = str(pathlib.Path(TMP, "iso.db"))
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

def make_epub(title):
    b = epub.EpubBook()
    b.set_identifier(title); b.set_title(title); b.set_language("en"); b.add_author("A")
    c = epub.EpubHtml(title="Ch1", file_name="c1.xhtml", lang="en")
    c.content = "<h1>Chapter 1</h1><p>Body text of the chapter.</p>"
    b.add_item(c); b.toc = (c,); b.add_item(epub.EpubNcx()); b.add_item(epub.EpubNav())
    b.spine = ["nav", c]
    p = pathlib.Path(TMP, f"{title}.epub"); epub.write_epub(str(p), b)
    return p.read_bytes()

def import_book(c, tok, title):
    return c.post("/api/import", cookies=as_(tok),
                  files={"files": (f"{title}.epub", make_epub(title), "application/epub+zip")})

with TestClient(app) as c:
    c.cookies.clear()

    # admin + a second user (bob) via invite
    c.post("/api/auth/login", json={"username": "admin", "password": "admin-pw-123"})
    admin_tok = c.cookies.get(COOKIE_NAME); c.cookies.clear()
    code = c.post("/api/auth/invites", cookies=as_(admin_tok)).json()["code"]
    c.post("/api/auth/register",
           json={"username": "bob", "password": "password123", "invite_code": code})
    bob_tok = c.cookies.get(COOKIE_NAME); c.cookies.clear()

    # admin creates a book + a collection
    ab = import_book(c, admin_tok, "Admins-Secret-Novel")
    check("admin-import-ok", ab.status_code == 201, str(ab.status_code))
    a_book = ab.json()["id"]
    a_coll = c.post("/api/collections", json={"name": "Admin Shelf"},
                    cookies=as_(admin_tok)).json()["id"]

    # --- bob is walled off from admin's book ---
    lib = c.get("/api/books", cookies=as_(bob_tok)).json()
    check("bob-library-excludes-admin-book", all(b["id"] != a_book for b in lib))
    check("bob-get-book-404", c.get(f"/api/books/{a_book}", cookies=as_(bob_tok)).status_code == 404)
    check("bob-chapters-404", c.get(f"/api/books/{a_book}/chapters", cookies=as_(bob_tok)).status_code == 404)
    check("bob-chapter-404", c.get(f"/api/books/{a_book}/chapters/1", cookies=as_(bob_tok)).status_code == 404)
    check("bob-cover-404", c.get(f"/api/books/{a_book}/cover", cookies=as_(bob_tok)).status_code == 404)
    check("bob-progress-get-404", c.get(f"/api/books/{a_book}/progress", cookies=as_(bob_tok)).status_code == 404)
    check("bob-progress-put-404",
          c.put(f"/api/books/{a_book}/progress", json={"mark_read": 1}, cookies=as_(bob_tok)).status_code == 404)
    check("bob-manifest-404",
          c.get(f"/api/books/{a_book}/chapters/1/audio/manifest", cookies=as_(bob_tok)).status_code == 404)
    check("bob-download-404", c.get(f"/api/books/{a_book}/download-all", cookies=as_(bob_tok)).status_code == 404)
    check("bob-patch-404",
          c.patch(f"/api/books/{a_book}", json={"rating": 5}, cookies=as_(bob_tok)).status_code == 404)
    check("bob-delete-book-404", c.delete(f"/api/books/{a_book}", cookies=as_(bob_tok)).status_code == 404)

    # --- bob is walled off from admin's collection ---
    check("bob-collections-exclude",
          all(x["id"] != a_coll for x in c.get("/api/collections", cookies=as_(bob_tok)).json()))
    check("bob-patch-coll-404",
          c.patch(f"/api/collections/{a_coll}", json={"name": "hax"}, cookies=as_(bob_tok)).status_code == 404)
    check("bob-delete-coll-404",
          c.delete(f"/api/collections/{a_coll}", cookies=as_(bob_tok)).status_code == 404)
    # bob can't attach admin's book to his own collection either
    b_coll = c.post("/api/collections", json={"name": "Bob Shelf"}, cookies=as_(bob_tok)).json()["id"]
    check("bob-assign-admin-book-404",
          c.put(f"/api/books/{a_book}/collections", json={"collection_ids": [b_coll]},
                cookies=as_(bob_tok)).status_code == 404)

    # --- positive controls: everyone can use their own; admin's data survived ---
    check("admin-still-sees-book", c.get(f"/api/books/{a_book}", cookies=as_(admin_tok)).status_code == 200)
    bb = import_book(c, bob_tok, "Bobs-Own-Novel")
    check("bob-import-own-ok", bb.status_code == 201)
    b_book = bb.json()["id"]
    bob_lib = c.get("/api/books", cookies=as_(bob_tok)).json()
    check("bob-sees-only-his", len(bob_lib) == 1 and bob_lib[0]["id"] == b_book)
    check("admin-cant-see-bobs", c.get(f"/api/books/{b_book}", cookies=as_(admin_tok)).status_code == 404)

    # --- deleting bob's account wipes his whole library (cascade), admin's survives ---
    import sqlite3
    bob_id = next(u["id"] for u in c.get("/api/auth/users", cookies=as_(admin_tok)).json()
                  if u["username"] == "bob")
    check("delete-bob-204",
          c.delete(f"/api/auth/users/{bob_id}", cookies=as_(admin_tok)).status_code == 204)
    db = sqlite3.connect(os.environ["NOVELSCRAPER_DB"])
    n_book = db.execute("SELECT count(*) FROM book WHERE id=?", (b_book,)).fetchone()[0]
    n_chap = db.execute("SELECT count(*) FROM chapter WHERE book_id=?", (b_book,)).fetchone()[0]
    n_vol = db.execute("SELECT count(*) FROM volume WHERE book_id=?", (b_book,)).fetchone()[0]
    n_admin_book = db.execute("SELECT count(*) FROM book WHERE id=?", (a_book,)).fetchone()[0]
    db.close()
    check("bobs-library-purged", n_book == 0 and n_chap == 0 and n_vol == 0,
          f"book={n_book} chap={n_chap} vol={n_vol}")
    check("admins-book-survived", n_admin_book == 1)

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
