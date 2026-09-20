# Contributing

Notes for working on this repo, mostly hard-won constraints that aren't obvious
from the code, and conventions worth keeping.

## Before you call something done

After any non-trivial change, read your own diff and check for:

- **Security**, injection (SQL, command, and SSRF especially, since this thing
  fetches arbitrary URLs), unsafe deserialisation, secrets in code, missing input
  validation, careless handling of fetched remote content, and authz gaps in the
  backend.
- **Bugs**, logic errors, unhandled edge cases, error handling, resource leaks,
  concurrency.
- **Performance**, N+1 queries, blocking I/O on hot paths, unbounded memory or
  request counts, missing rate limiting or caching in the scraper.

Then say what you actually checked, not "reviewed".

## Scraping conduct

Be mindful of target sites: respect `robots.txt` where applicable, rate-limit
requests, and set sensible timeouts/retries. Don't write anything that hammers a
source.

**Cloudflare-gated sources & datacenter-IP blocks.** Some sources (novelphoenix →
novelfire, freewebnovel, novelhall) 403 from the VPS but not from a residential IP, Cloudflare blocks the Hetzner datacenter ASN by reputation, and no fingerprint/IP
change on a cloud host fixes it (only a residential IP passes). Don't burn effort on
impersonation tweaks or new server IPs for these. The workarounds are importing
an EPUB exported from a machine on a normal connection, or letting the Android
app relay the fetch through the phone.

## Frontend conventions

Established during a maintainability pass, written down so the same debt
doesn't grow back. Prefer these over re-rolling local variants:

- **Overlay dismissal** → `useDismiss(active, onDismiss, opts)` from
  `lib/hooks.ts`. Do NOT hand-roll outside-click / Escape / scroll-close effects
  in components (there were three near-identical copies). `opts.refs` are the
  elements whose clicks should NOT dismiss.
- **Enter ("grow-in") animation** → `useEnterTransition(active?)` from
  `lib/hooks.ts`, not a local `useState(false)` + `requestAnimationFrame` effect.
- **EPUB file inputs** → `EPUB_ACCEPT` + `pickEpubs()` from `lib/epub.ts`. Never
  re-inline the `.epub` extension filter or the `accept` string.
- **HTTP** → go through the `api` object + `req`/`upload` in `lib/api.ts` (which
  centralise `ApiError`/`detail` parsing); response shapes live in `lib/types.ts`
  (no inline response types on `api.*`). Data fetching is TanStack Query hooks in
  `lib/queries.ts`, import types at the top, don't use inline `import("./types")`.
- **No dead code**: the audit removed an unused `ui/select.tsx`, `CardHeader`, and
  write-only browser-tts helpers. Before adding an exported helper/component,
  confirm it has a consumer; delete it when the last one goes. Keep doc comments
  truthful (a stale "shared by X" comment described a consumer that didn't exist).
- **Magic values**: hoist repeated literals (e.g. scrape defaults) to named
  consts; use `cn()` for conditional classes rather than bespoke string joiners.
- **Deliberately left large & cohesive**: `components/tts-player.tsx` and the
  reader `app/read/[id]/[position]/page.tsx` hold delicate, hard-won background-
  audio and scroll-restore logic that can't be verified headlessly. Don't split
  or "tidy" them without an on-device test plan: the regression risk outweighs
  the readability gain. A `useMediaSession` extraction is the one safe next step.
- After UI-behaviour refactors (menus, dialogs, gestures), verify with a
  throwaway Playwright pass (mock `/api/*` via `page.route`, `next start` on a
  spare port) before claiming done, `tsc`/`build` passing does not prove the
  interaction still works.

## Backend conventions

- **Domain logic lives in `app/services/`, not in routers.** Reading-progress /
  word-count / reading-time logic is in `services/reading.py`; book→`BookRead`
  serialization is in `services/library.py`. The bug this fixes: routers were
  importing each other's *private* helpers (`stats.py` pulled `_ensure_word_counts`
  from `books.py`; `imports.py` pulled `_book_read`), which couples routers and
  duplicates rules. If two routers need the same logic, it belongs in a service, never `from .other_router import _private`.
- **One `BookRead` shape everywhere.** All book-returning endpoints go through
  `services.library.book_read` / `books_read` (bulk, N+1-free). Don't hand-build a
  partial `BookRead` in an endpoint, that's how `PUT /collections` ended up
  omitting `volumes` while every other endpoint included them.
- **A shared helper imported by another module must be public.** `parser.sanitize`
  and `enumerators.format_url` were `_private` yet imported across modules; a
  leading underscore says "module-internal", so either make it public or move it.
- **Cache writes that two requests can race must be atomic** (temp file + `os.replace`),
  e.g. the TTS audio-chunk cache: a plain `write_bytes` can serve a half-written
  file to a concurrent reader.
- **Verify backend changes against both suites** before claiming done:
  `.venv/bin/python tests/smoke_api.py` (full API lifecycle incl. TTS) and
  `tests/test_migration.py` (startup auto-migration). Add an assertion when a
  refactor changes a response shape (the collections check was added because
  `set_book_collections` began returning `volumes`).
- **Deliberately left as-is** (cohesive, security-sensitive, delicate): the
  `AsyncFetcher`/pacing stack (SSRF guard, robots, adaptive rate-limit) and the
  `JobManager` async worker. Don't "tidy" these without a scrape-level test plan.
- **Startup DB migrations** are additive-only (`ALTER TABLE ADD COLUMN` + NULL
  backfill in `db.py`); SQLModel never alters existing tables. Adding a non-Optional
  model column is safe; renames/drops/indexes need a real migration tool.
- **Auth & per-user isolation (multi-user).** Every data router requires a valid
  session (`get_current_user` applied at the router level in `main.py`); admin-only
  actions use `get_admin`. Data is owned per user: `Book`/`Collection`/`Job`/
  `ArchivedProgress` carry `user_id`, and child rows inherit ownership through their
  parent `Book`/`Collection`. **Every query must scope to the caller**, use
  `_owned_book`/`_owned_job`/`_owned_collection` (or `.where(... .user_id == user.id)`),
  and single-item lookups **404 (never 403) on rows the user doesn't own** so ids
  don't leak. New rows must set `user_id`; `manager.submit` takes the owner. Sessions
  are DB-backed opaque tokens in an httpOnly cookie (`app/auth.py`); passwords are
  stdlib scrypt (`app/security.py`, no external crypto deps). The cross-user
  `tests/test_isolation.py` must stay green: a missing scope there is a data leak.
