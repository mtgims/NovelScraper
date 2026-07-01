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

# Create an "old" job table that predates the source_url column.
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
con.commit(); con.close()

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from app.db import init_db, engine
from sqlmodel import Session, select
from app.models import Job

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

# Idempotent: running again is a no-op (no error).
init_db()
check("idempotent", True)

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
