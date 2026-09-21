#!/usr/bin/env bash
# Push the working tree to the server for a Docker redeploy — with the right
# excludes so it never ships the Android app, scratch worktrees, build caches, or
# your data/secrets. THIS is the deploy command.
#
# Usage:
#   deploy/push.sh [user@]host:/path/          # rsync only
#   deploy/push.sh root@vps:/opt/novelscraper/ # e.g.
#
# After it syncs, on the server run:
#   cd /opt/novelscraper && docker compose up -d --build
# (or, for the bare-metal systemd setup: `ns restart`)
set -euo pipefail

DEST=${1:?usage: deploy/push.sh [user@]host:/path/   e.g. root@vps:/opt/novelscraper/}
REPO="$(cd "$(dirname "$0")/.." && pwd)"

rsync -avz --delete \
  --exclude '.git' \
  --exclude '.env' \
  --exclude '.claude' \
  --exclude '/app/' \
  --exclude 'WebCode' \
  --exclude '*.apk' \
  --exclude '*.AppImage' \
  --exclude 'CHAT-HANDOFF.md' \
  --exclude 'backend/data' \
  --exclude 'backend/.venv' \
  --exclude 'backend/models' \
  --exclude 'frontend/node_modules' \
  --exclude 'frontend/.next' \
  "$REPO/" "$DEST"

echo
echo "✓ synced to $DEST"
echo "  next, on the server:  cd <path> && docker compose up -d --build"
