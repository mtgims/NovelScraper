#!/usr/bin/env bash
#
# NovelScraper launcher — starts the backend (API) and frontend (web UI)
# together. First run installs anything missing. Press Ctrl+C to stop both.
#
#   ./start.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/frontend"
API_PORT="${API_PORT:-8000}"
WEB_PORT="${WEB_PORT:-3000}"

info()  { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
error() { printf '\033[1;31merror:\033[0m %s\n' "$1" >&2; }

# --- first-run setup --------------------------------------------------------

if [ ! -x "$BACKEND/.venv/bin/uvicorn" ]; then
  info "Setting up backend (one-time)…"
  command -v python3 >/dev/null || { error "python3 not found"; exit 1; }
  python3 -m venv "$BACKEND/.venv" || { error "could not create venv"; exit 1; }
  "$BACKEND/.venv/bin/pip" install -q --upgrade pip
  "$BACKEND/.venv/bin/pip" install -q -r "$BACKEND/requirements.txt" \
    || { error "backend dependency install failed"; exit 1; }
fi

if [ ! -d "$FRONTEND/node_modules" ]; then
  info "Installing frontend dependencies (one-time)…"
  command -v npm >/dev/null || { error "npm not found"; exit 1; }
  ( cd "$FRONTEND" && npm install --no-audit --no-fund ) \
    || { error "frontend dependency install failed"; exit 1; }
fi

if [ ! -f "$FRONTEND/.env.local" ]; then
  cp "$FRONTEND/.env.local.example" "$FRONTEND/.env.local"
fi

# --- run both, clean up on exit --------------------------------------------

# Kill a process and all its descendants (next-server is a grandchild of npm).
kill_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null
}

API_PID=""
WEB_PID=""
cleanup() {
  trap - EXIT INT TERM
  info "Stopping…"
  [ -n "$WEB_PID" ] && kill_tree "$WEB_PID"
  [ -n "$API_PID" ] && kill_tree "$API_PID"
}
trap cleanup EXIT INT TERM

info "Starting API on http://127.0.0.1:${API_PORT}"
( cd "$BACKEND" && exec .venv/bin/uvicorn app.main:app --port "$API_PORT" ) &
API_PID=$!

info "Starting web UI on http://localhost:${WEB_PORT}"
( cd "$FRONTEND" && exec npm run dev -- --port "$WEB_PORT" ) &
WEB_PID=$!

cat <<EOF

  ┌────────────────────────────────────────────┐
  │  NovelScraper is starting up…              │
  │                                            │
  │  App:  http://localhost:${WEB_PORT}                 │
  │  API:  http://127.0.0.1:${API_PORT}/docs            │
  │                                            │
  │  Press Ctrl+C to stop.                     │
  └────────────────────────────────────────────┘

EOF

wait
