"""Database engine and session helpers (SQLite via SQLModel)."""

from __future__ import annotations

import logging

from sqlalchemy import inspect, text
from sqlmodel import Session, SQLModel, create_engine

from .settings import settings

logger = logging.getLogger(__name__)

# check_same_thread=False: the engine is shared between the job worker (event
# loop thread) and request handlers (threadpool). timeout softens SQLite's
# single-writer locking under that light concurrency.
engine = create_engine(
    f"sqlite:///{settings.db_path}",
    connect_args={"check_same_thread": False, "timeout": 30},
)


def _migrate_add_columns() -> None:
    """Add any columns present on the models but missing from existing tables.

    SQLModel.create_all() creates missing *tables* but never alters existing
    ones, so adding a field to a model would otherwise break an older DB. This
    handles the common, safe case (new nullable / defaulted columns) via
    ALTER TABLE ADD COLUMN. It does not drop/rename columns or add indexes —
    use a real migration tool if the schema ever needs that.
    """
    inspector = inspect(engine)
    existing_tables = set(inspector.get_table_names())
    for table_name, table in SQLModel.metadata.tables.items():
        if table_name not in existing_tables:
            continue  # newly created by create_all; already complete
        have = {c["name"] for c in inspector.get_columns(table_name)}
        for column in table.columns:
            if column.name not in have:
                col_type = column.type.compile(dialect=engine.dialect)
                ddl = f'ALTER TABLE "{table_name}" ADD COLUMN "{column.name}" {col_type}'
                with engine.begin() as conn:
                    conn.execute(text(ddl))
                logger.info("db migration: added %s.%s", table_name, column.name)

            # SQLite's ADD COLUMN leaves existing rows NULL (the model's Python
            # default is not a SQL default), which breaks non-Optional response
            # fields. Backfill NULLs to the column's scalar default so a
            # freshly-added column like `sort_order` reads as 0, not NULL.
            default = column.default
            if default is not None and getattr(default, "is_scalar", False):
                with engine.begin() as conn:
                    conn.execute(
                        text(
                            f'UPDATE "{table_name}" SET "{column.name}" = :val '
                            f'WHERE "{column.name}" IS NULL'
                        ),
                        {"val": default.arg},
                    )


def _backfill_book_source_urls() -> None:
    """Books scraped before source_url existed have it NULL, so the update
    feature can't re-scrape them. Recover the URL from the job that scraped them
    (matched by slug + site) where that job record still exists."""
    with engine.begin() as conn:
        conn.execute(text(
            """
            UPDATE book SET source_url = (
                SELECT j.source_url FROM job j
                WHERE j.book_slug = book.slug AND j.site = book.site
                  AND j.source_url IS NOT NULL
                LIMIT 1
            )
            WHERE source_url IS NULL AND EXISTS (
                SELECT 1 FROM job j
                WHERE j.book_slug = book.slug AND j.site = book.site
                  AND j.source_url IS NOT NULL
            )
            """
        ))


def _compress_chapter_content() -> None:
    """One-time: compress chapter HTML still stored as plain TEXT (from before
    the column used CompressedText), then VACUUM to reclaim the freed space."""
    import gzip

    with engine.begin() as conn:
        rows = conn.execute(
            text("SELECT id, content FROM chapter WHERE typeof(content) = 'text'")
        ).fetchall()
        if not rows:
            return
        for cid, content in rows:
            if content is not None:
                conn.execute(
                    text("UPDATE chapter SET content = :c WHERE id = :id"),
                    {"c": gzip.compress(content.encode("utf-8"), 6), "id": cid},
                )
        logger.info("compressed %d chapter(s) of content", len(rows))
    # VACUUM must run outside a transaction.
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as conn:
        conn.exec_driver_sql("VACUUM")


def _cleanup_stored_epubs() -> None:
    """EPUBs are now built on demand, so any previously-written .epub files are
    dead weight — remove them to reclaim the space (they regenerate on download)."""
    out = settings.output_dir
    if not out.exists():
        return
    freed = 0
    for f in out.rglob("*.epub"):
        try:
            freed += f.stat().st_size
            f.unlink()
        except OSError:
            pass
    if freed:
        logger.info("removed stored EPUBs, reclaimed %.1f MB", freed / 1e6)


def init_db() -> None:
    settings.ensure_dirs()
    # Import models so they register on SQLModel.metadata before create_all.
    from . import models  # noqa: F401

    SQLModel.metadata.create_all(engine)
    _migrate_add_columns()
    _backfill_book_source_urls()
    _compress_chapter_content()
    _cleanup_stored_epubs()


def get_session():
    """FastAPI dependency yielding a session."""
    with Session(engine) as session:
        yield session
