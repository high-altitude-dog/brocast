#!/bin/bash
# Step 1 on the phones, over adb. Run from pears/android-test.
#   ./phone.sh install SERIAL...                       install the debug APK (never touches org.beachsync.proto)
#   ./phone.sh start STATION_KEY IP:PORT SERIAL...     start a fresh run on each phone
#                                                      step 2: IP:PORT "-" = public HyperDHT, LAN shortcut off;
#                                                      RELAY=<blind relay key> adds a relay fallback;
#                                                      FORCE_RELAY=1 also sends phone-to-phone links only through it
#   ./phone.sh peek SERIAL...                          the last engine event lines
#   ./phone.sh pull SERIAL...                          stop the run, then copy results to results/<serial>/
set -e
PKG=org.beachsync.pearstest
cmd=$1; shift
case $cmd in
  install) for s in "$@"; do adb -s "$s" install -r app/build/outputs/apk/debug/app-debug.apk; done ;;
  start) key=$1; boot=$2; shift 2
    for s in "$@"; do adb -s "$s" shell am start -n $PKG/.MainActivity --es station "$key" --es bootstrap "$boot" ${RELAY:+--es relay $RELAY} ${FORCE_RELAY:+--ez forceRelay true} --ez go true; done ;;
  peek) for s in "$@"; do echo "== $s"; adb -s "$s" exec-out run-as $PKG tail -n 3 files/run.jsonl; done ;;
  pull) for s in "$@"; do adb -s "$s" shell am start -n $PKG/.MainActivity --ez stop true; done
    sleep 5
    for s in "$@"; do
      d=results/$s; rm -rf "$d"; mkdir -p "$d/music"
      for f in run.jsonl host.json status.json; do adb -s "$s" exec-out run-as $PKG cat files/$f > "$d/$f"; done
      for f in $(adb -s "$s" exec-out run-as $PKG ls files/music | grep '\.opus$'); do
        adb -s "$s" exec-out run-as $PKG cat "files/music/$f" > "$d/music/$f"
      done
      echo "$s: $(ls "$d/music" | wc -l) sets pulled"
    done ;;
  *) echo "usage: see the header"; exit 1 ;;
esac
