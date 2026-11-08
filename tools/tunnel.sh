#!/bin/sh
# Opens (or with "stop", closes) the SSH tunnel to the upload page at http://127.0.0.1:8766/. It runs in the background.
set -eu
. "$HOME/.config/brocast.env"
PID=/tmp/brocast-tunnel.pid
if [ "${1:-}" = stop ]; then [ -f $PID ] && kill "$(cat $PID)" 2>/dev/null; rm -f $PID; echo "Tunnel closed."; exit 0; fi
[ -f $PID ] && kill -0 "$(cat $PID)" 2>/dev/null && { echo "Already open: http://127.0.0.1:8766/"; exit 0; }
# shellcheck disable=SC2086
ssh $BROCAST_SSH -o ExitOnForwardFailure=yes -f -N -L 8766:127.0.0.1:8766 && pgrep -nf "8766:127.0.0.1:8766" > $PID
echo "Open: http://127.0.0.1:8766/  (close with tools/tunnel.sh stop)"
