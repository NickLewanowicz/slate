#!/usr/bin/env bash
# Build the PUBLIC GitHub export of Slate from this private working tree.
# - Fresh git history (v1 history contains user data + internal artifacts).
# - Internal tooling/process dirs excluded.
# - Homelab-specific hosts/IPs genericized.
#
# Usage: scripts/build-public.sh /tmp/slate-public
set -euo pipefail
DEST="${1:?destination dir required}"
SRC="$(cd "$(dirname "$0")/.." && pwd)"

rm -rf "$DEST"
mkdir -p "$DEST"

# 1. Copy the tree, excluding private/internal paths.
rsync -a \
  --exclude '.git' --exclude '.claude' --exclude '.gitea' \
  --exclude 'qa/' --exclude 'qa-screenshots/' \
  --exclude 'CLAUDE.md' --exclude '.env' --exclude '.env.example' \
  --exclude 'docs/v2/' --exclude 'deploy/DEPLOY.md' \
  --exclude 'backend/coverage' --exclude '**/node_modules' \
  --exclude '.playwright-mcp' --exclude 'site_*.png' \
  "$SRC/" "$DEST/"

cd "$DEST"

# 2. Root changelog (public convention) from the internal night log.
if [ -f docs/v2/CHANGELOG.md ]; then
  { echo "# Changelog"; echo; tail -n +2 docs/v2/CHANGELOG.md; } > CHANGELOG.md
fi
rm -rf docs/v2

# 2b. Public compose file: drop internal-approval comment.
if [ -f deploy/docker-compose.prod.yml ]; then
  sed -i '' '/Deployed ONLY after explicit owner approval/d' deploy/docker-compose.prod.yml
fi

# 3. Genericize homelab-specific references.
while IFS= read -r f; do
  sed -i '' 's|https://github.com/NickLewanowicz/slate|https://github.com/NickLewanowicz/slate|g; s|github.com/NickLewanowicz/slate|github.com/NickLewanowicz/slate|g' "$f"
done < <(grep -rl "github.com/NickLewanowicz/slate" . || true)
while IFS= read -r f; do
  sed -i '' 's|https://slate.example.com|https://slate.example.com|g; s|slate.example.com|slate.example.com|g' "$f"
done < <(grep -rl "slate.example.com" . || true)
while IFS= read -r f; do
  sed -i '' 's|https://github.com/NickLewanowicz|https://github.com/NickLewanowicz|g; s|github.example.com|github.example.com|g' "$f"
done < <(grep -rl "github.example.com" . || true)
while IFS= read -r f; do
  sed -i '' -E 's|192\.168\.86\.[0-9]+|192.168.1.10|g' "$f"
done < <(grep -rlE "192\.168\.86\.[0-9]+" . || true)

# 4. Fresh history.
git init -q -b main
git add -A
git commit -q -m "Slate v2 — declarative home-screen widget for AI agents

Backend (Bun/Elysia/SQLite), Android widget + companion app (Kotlin,
RemoteViews + Compose), OpenClaw skill, docs site. Contract frozen in
schema/. >=80% branch coverage enforced on both sides."

echo "public export ready: $DEST"
