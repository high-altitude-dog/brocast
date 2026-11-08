#!/bin/sh
# Commits everything and pushes the working branch and the public default branch, after checking nothing private would go out.
# Usage: tools/ship.sh "What changed". The patterns to refuse live in ~/.config/brocast.env, outside the repo.
set -eu
[ $# -ge 1 ] || { echo 'Usage: tools/ship.sh "message"'; exit 1; }
. "$HOME/.config/brocast.env"
cd "$(dirname "$0")/.."
git add -A
if git diff --cached | grep -i -E "$BROCAST_FORBID" >/dev/null || git diff --cached --name-only | grep -i -E "$BROCAST_FORBID" >/dev/null; then
  git reset -q; echo "Stopped: something private is in the change. Nothing committed."; exit 1
fi
if git diff --cached --quiet; then echo "Nothing to commit."; else git commit -qm "$1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"; fi
git push -q origin prototype-work-2026-11-02 && git push -q origin prototype-work-2026-11-02:prototype-queue
echo "Pushed $(git log --oneline -1)"
