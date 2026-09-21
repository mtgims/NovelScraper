"""Reading-progress export (GET /api/stats/export): both formats return the
caller's books with where they are. Guards against the endpoint breaking
unnoticed (it raised NameError after the stats query refactor). Run with the
project venv:

    cd backend && .venv/bin/python tests/test_stats_export.py
"""

import csv, io, os, tempfile, pathlib, sys

TMP = tempfile.mkdtemp(prefix="ns_export_")
os.environ["NOVELSCRAPER_DATA_DIR"] = str(pathlib.Path(TMP, "data"))
os.environ["NOVELSCRAPER_DB"] = str(pathlib.Path(TMP, "export.db"))
PROFILES = pathlib.Path(TMP, "profiles"); PROFILES.mkdir()
os.environ["NOVELSCRAPER_PROFILE_DIR"] = str(PROFILES)
os.environ["NOVELSCRAPER_ADMIN_USERNAME"] = "admin"
os.environ["NOVELSCRAPER_ADMIN_PASSWORD"] = "admin-pw-123"

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from ebooklib import epub
from app.main import app

ok = []
def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(f"[{name}] {'PASS' if cond else 'FAIL'} {extra}")

def make_epub(title, chapters):
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

with TestClient(app) as c:
    c.post("/api/auth/login", json={"username": "admin", "password": "admin-pw-123"})
    r = c.post("/api/import", files={"files": ("Export-Novel.epub", make_epub("Export-Novel", 4),
                                               "application/epub+zip")})
    check("import-ok", r.status_code == 201, str(r.status_code))
    book = r.json()["id"]
    c.put(f"/api/books/{book}/progress", json={"mark_positions": [1, 2]})

    j = c.get("/api/stats/export")
    check("json-200", j.status_code == 200, str(j.status_code))
    rows = j.json() if j.status_code == 200 else []
    row = next((x for x in rows if x.get("title") == "Export-Novel"), None)
    check("json-has-book", row is not None, str(rows)[:200])
    if row:
        check("json-counts", (row["chapters_read"], row["total_chapters"]) == (2, 4), str(row))
        check("json-percent", row["percent_read"] == 50.0, str(row))

    s = c.get("/api/stats/export?format=csv")
    check("csv-200", s.status_code == 200, str(s.status_code))
    check("csv-attachment", "attachment" in s.headers.get("content-disposition", ""),
          s.headers.get("content-disposition", ""))
    lines = list(csv.reader(io.StringIO(s.text)))
    check("csv-header-and-row", len(lines) >= 2 and "Export-Novel" in lines[1], str(lines[:2]))

print(f"\n{sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
