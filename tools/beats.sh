#!/bin/sh
# Beat analysis on each connected phone: sets with a current grid, of sets downloaded, and the last few finished.
for d in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  L=/sdcard/Android/data/org.beachsync.proto/files/library
  sets=$(timeout 15 adb -s "$d" shell ls $L 2>/dev/null | grep -c '\.opus$')
  done=$(timeout 30 adb -s "$d" shell "for f in $L/*.beats; do head -1 \$f; done" 2>/dev/null | grep -c 'beats 2')
  echo "$d: $done of $sets sets have beats"
  timeout 15 adb -s "$d" logcat -d -s SetBeats:I 2>/dev/null | grep windows | tail -2 | sed 's/^/  /'
done
