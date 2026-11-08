#!/bin/sh
# Builds the app (with the USB drive cooldown) and installs it on every phone adb can see, then opens it. Run from anywhere.
set -eu
cd "$(dirname "$0")/../android"
LOG=/tmp/brocast-build.log
bash "$HOME/.claude/skills/usb-cooldown/cooldown.sh" -- bash -c "./gradlew assembleDebug -q > $LOG 2>&1" >/dev/null || true
if grep -qE '^e: |FAILED' "$LOG"; then grep -E '^e: |FAILED' "$LOG" | head -5; echo "Build failed: see $LOG"; exit 1; fi
for d in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  echo "$d: $(timeout 120 adb -s "$d" install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1)"
  timeout 10 adb -s "$d" shell am start -n org.beachsync.proto/.MainActivity >/dev/null 2>&1 || true
done
adb devices | awk 'NR>1 && $2!="device" && NF {print $1 ": " $2 " (accept the prompt on the phone)"}'
