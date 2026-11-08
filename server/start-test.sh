#!/bin/sh
# Starts the test stream (brocast-test, then brocast-dht) on the test server, and stops both by themselves after
# MINUTES (default 10), on the server itself, so they stop even if this laptop goes away. Running it again restarts
# the timer. The login stays out of this public repo: set BROCAST_SSH, for example "-i ~/.ssh/<key> <user>@<server-ip>".
set -eu
MINUTES="${1:-10}"
[ -f "$HOME/.config/brocast.env" ] && . "$HOME/.config/brocast.env"
: "${BROCAST_SSH:?set BROCAST_SSH in ~/.config/brocast.env}"
# shellcheck disable=SC2086
ssh $BROCAST_SSH "set -e
  [ -f ~/brocast-autostop.pid ] && kill \$(cat ~/brocast-autostop.pid) 2>/dev/null || true
  docker start brocast-test >/dev/null && docker start brocast-dht >/dev/null
  nohup sh -c 'sleep $((MINUTES * 60)); docker stop brocast-dht brocast-test' >/dev/null 2>&1 &
  echo \$! > ~/brocast-autostop.pid
  echo started, stopping in $MINUTES min"
