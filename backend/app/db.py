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
            if column.name in have:
                continue
            col_type = column.type.compile(dialect=engine.dialect)
            ddl = f'ALTER TABLE "{table_name}" ADD COLUMN "{column.name}" {col_type}'
            with engine.begin() as conn:
                conn.execute(text(ddl))
            logger.info("db migration: added %s.%s", table_name, column.name)


def init_db() -> None:
    settings.ensure_dirs()
    # Import models so they register on SQLModel.metadata before create_all.
    from . import models  # noqa: F401

    SQLModel.metadata.create_all(engine)
    _migrate_add_columns()


def get_session():
    """FastAPI dependency yielding a session."""
    with Session(engine) as session:
        yield session
