"""Regression test for the startup column auto-migration.

Simulates an older DB (a `job` table missing a newer column) and verifies
init_db() heals it via ALTER TABLE ADD COLUMN. Run with the project venv:

    cd backend && .venv/bin/python tests/test_migration.py
"""

import os, sqlite3, tempfile, pathlib, sys

TMP = tempfile.mkdtemp(prefix="ns_mig_")
DB = pathlib.Path(TMP, "old.db")
os.environ["NOVELSCRAPER_DATA_DIR"] = TMP
os.environ["NOVELSCRAPER_DB"] = str(DB)
# So the bootstrap creates an admin and claims the pre-auth rows below.
os.environ["NOVELSCRAPER_ADMIN_USERNAME"] = "root"
os.environ["NOVELSCRAPER_ADMIN_PASSWORD"] = "s3cret-pw"

# Create an "old" job table that predates the source_url + user_id columns.
con = sqlite3.connect(DB)
con.execute(
    "CREATE TABLE job (id TEXT PRIMARY KEY, site TEXT, book_slug TEXT, "
    "status TEXT, phase TEXT, chapters_per_volume INTEGER, total_chapters INTEGER, "
    "fetched_chapters INTEGER, skipped_chapters INTEGER, error TEXT, book_id INTEGER, "
    "created_at TEXT, started_at TEXT, finished_at TEXT)"
)
con.execute(
    "INSERT INTO job (id, site, book_slug, status, phase) "
    "VALUES ('j1', 'mock', 'a-book', 'completed', 'done')"
)
# Old-shape archivedprogress: composite PK (site, slug), no id / user_id.
con.execute(
    "CREATE TABLE archivedprogress (site TEXT, slug TEXT, source_url TEXT, "
    "last_position INTEGER, scroll REAL, read_positions TEXT, updated_at TEXT, "
    "PRIMARY KEY (site, slug))"
)
con.execute(
    "INSERT INTO archivedprogress (site, slug, last_position, scroll, "
    "read_positions, updated_at) VALUES "
    "('mock', 'a-book', 5, 0.5, '[1, 2, 3]', '2026-01-01T00:00:00')"
)
con.commit(); con.close()

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from app.db import init_db, engine
from sqlmodel import Session, select
from app.models import ArchivedProgress, Invite, Job, User, UserSession

ok = []
def check(name, cond):
    ok.append(cond)
    print(f"[{name}] {'PASS' if cond else 'FAIL'}")

before = {r[1] for r in sqlite3.connect(DB).execute("PRAGMA table_info(job)")}
check("precondition-missing-source_url", "source_url" not in before)

init_db()  # should add the missing column

after = {r[1] for r in sqlite3.connect(DB).execute("PRAGMA table_info(job)")}
check("source_url-added", "source_url" in after)
check("delay-added", "delay" in after)
check("concurrency-added", "concurrency" in after)

# The ORM query that used to 500 must now work, preserving the existing row.
with Session(engine) as s:
    jobs = s.exec(select(Job)).all()
check("existing-row-preserved", len(jobs) == 1 and jobs[0].source_url is None)

# --- auth migration: ownership columns, new tables, bootstrap + backfill ---
check("job-user_id-added", "user_id" in after)

ap_cols = {r[1] for r in sqlite3.connect(DB).execute("PRAGMA table_info(archivedprogress)")}
check("archivedprogress-rebuilt", {"id", "user_id"} <= ap_cols)

tables = {r[0] for r in sqlite3.connect(DB).execute(
    "SELECT name FROM sqlite_master WHERE type='table'")}
check("auth-tables-created", {"user", "usersession", "invite"} <= tables)

with Session(engine) as s:
    admins = s.exec(select(User)).all()
    admin = admins[0] if admins else None
    check("admin-created", admin is not None and admin.is_admin and admin.username == "root")
    # Pre-auth rows were claimed by the admin (backfill).
    job = s.exec(select(Job)).first()
    check("job-backfilled-to-admin", admin is not None and job.user_id == admin.id)
    ap = s.exec(select(ArchivedProgress)).all()
    check("archived-row-preserved-and-owned",
          len(ap) == 1 and ap[0].user_id == admin.id
          and ap[0].site == "mock" and ap[0].last_position == 5)

# --- index migration: create_all() only builds indexes when it creates the
# table, and ADD COLUMN never brings one, so an upgraded DB was missing every
# index declared after its tables first existed (measured: all three user_id
# indexes were absent on the real dev DB).
def indexes_on(table):
    return {r[1] for r in sqlite3.connect(DB).execute(
        f"SELECT type, name FROM sqlite_master WHERE type='index' AND tbl_name='{table}'")}

check("job-user_id-index-created", "ix_job_user_id" in indexes_on("job"))
check("job-status-index-created", "ix_job_status" in indexes_on("job"))
check("chapter-composite-index-created",
      "ix_chapter_book_id_position" in indexes_on("chapter"))
check("book-user_id-index-created", "ix_book_user_id" in indexes_on("book"))
check("collection-user_id-index-created",
      "ix_collection_user_id" in indexes_on("collection"))
# The composite index must actually be chosen for the reader's lookup.
plan = " ".join(str(r[-1]) for r in sqlite3.connect(DB).execute(
    "EXPLAIN QUERY PLAN SELECT id FROM chapter WHERE book_id = 1 AND position = 2"))
check("chapter-lookup-uses-composite-index", "ix_chapter_book_id_position" in plan)

# --- WAL: readers must not be blocked by the scrape worker's writes ---
journal = sqlite3.connect(DB).execute("PRAGMA journal_mode").fetchone()[0]
check("journal-mode-is-wal", journal.lower() == "wal")

# Idempotent: running again is a no-op (no error, no duplicate admin,
# no attempt to recreate an existing index).
init_db()
with Session(engine) as s:
    check("idempotent", len(s.exec(select(User)).all()) == 1)

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
