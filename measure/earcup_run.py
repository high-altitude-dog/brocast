#!/usr/bin/env python3
"""Runs the earcup test one or more times over adb and logs each result with the Bluetooth codec details.

    python3 measure/earcup_run.py --runs 5          # 10 s countdown before each run, then about 15 s of clicks

The phone makes no sound until each countdown ends, and each run stops by itself. Hold the ear cup over the phone's mic first.

For each run it reads the app's own row from earcup-results.csv on the phone, and the Bluetooth stack's current codec,
adaptive-bitrate state and the headset's reported delay (the A2DP delay report). It adds one row per run to
measure/earcup-results.csv, which git ignores. The codec is read just before and just after the run.
Needs adb. The Galaxy is the default phone; pass --serial for another.
"""
import argparse
import csv
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "earcup-results.csv")
FILES = "/sdcard/Android/data/org.beachsync.proto/files"
PACKAGE = "org.beachsync.proto"


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True).stdout


def screen(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb(serial, "shell", "cat", "/sdcard/ui.xml")


def centre(xml, attr, label):
    m = re.search(r'%s="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % (attr, re.escape(label)), xml)
    if not m:
        return None
    a = list(map(int, m.groups()))
    return (a[0] + a[2]) // 2, (a[1] + a[3]) // 2


def tap(serial, point):
    adb(serial, "shell", "input", "tap", str(point[0]), str(point[1]))


def to_setup(serial):
    """Gets the app to the earcup test's first step, silently."""
    for _ in range(4):
        x = screen(serial)
        if 'text="Start"' in x and "PLACE THE CUP" in x:
            return centre(x, "text", "Start")
        p = centre(x, "text", "Try again")
        if p:
            tap(serial, p)
        elif centre(x, "text", "Earcup test · about 15 s"):
            tap(serial, centre(x, "text", "Earcup test · about 15 s"))
        elif centre(x, "content-desc", "Calibration"):
            tap(serial, centre(x, "content-desc", "Calibration"))
        else:
            adb(serial, "shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(1.5)
    sys.exit("Couldn't reach the earcup test's first step. Open Calibration on the phone and try again.")


def bluetooth(serial):
    """The codec, adaptive-bitrate state and delay report as the Bluetooth stack sees them now."""
    d = adb(serial, "shell", "dumpsys", "bluetooth_manager")
    codec = re.search(r"^\s*mCodecConfig: \{codecName:(\w+),.*?mSampleRate:0x\w+\((\d+)\).*?mBitsPerSample:0x\w+\((\d+)\)", d, re.M)
    delay = re.search(r"mDelayReport: (\d+)", d)
    mode = re.search(r"LDAC quality mode\s*:\s*(\S+)", d)
    kbps = re.search(r"LDAC transmission bitrate \(Kbps\)\s*:\s*(\d+)", d)
    return {
        "codec": codec.group(1) if codec else "",
        "sample_rate": codec.group(2) if codec else "",
        "bits": codec.group(3) if codec else "",
        "delay_report_ms": delay.group(1) if delay else "",
        "ldac_mode": mode.group(1) if mode else "",
        "ldac_kbps": kbps.group(1) if kbps else "",
    }


def last_app_row(serial):
    text = adb(serial, "shell", "cat", f"{FILES}/earcup-results.csv")
    rows = list(csv.reader(text.splitlines()))
    if len(rows) < 2:
        return None, None
    return rows[0], rows[-1]


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--serial", default="R5CX93ZA1TN", help="adb serial; the Galaxy by default")
    p.add_argument("--runs", type=int, default=1)
    p.add_argument("--note", default="", help="free text for the note column, for example 'codec forced to SBC'")
    a = p.parse_args()

    for i in range(a.runs):
        before = bluetooth(a.serial)
        start = to_setup(a.serial)
        print(f"Run {i + 1} of {a.runs}: codec {before['codec'] or '?'}, reported delay {before['delay_report_ms'] or '?'} ms.", flush=True)
        for n in range(10, 0, -1):
            print(n, flush=True)
            time.sleep(1)
        print("0: clicks start", flush=True)
        tap(a.serial, start)
        time.sleep(22)
        after = bluetooth(a.serial)
        head, row = last_app_row(a.serial)
        if row is None:
            print("No result row on the phone.")
            continue
        app = dict(zip(head, row))
        out = {
            "time": app.get("time", ""), "phone": app.get("phone", ""), "output": app.get("output", ""), "result": app.get("result", ""),
            "later_ms": app.get("later_ms", ""), "speaker_ms": app.get("speaker_ms", ""), "headphones_ms": app.get("headphones_ms", ""),
            "speaker_scatter_ms": app.get("speaker_scatter_ms", ""), "headphones_scatter_ms": app.get("headphones_scatter_ms", ""),
            "mic_source": app.get("mic_source", ""),
            "speaker_drift_start_ms": app.get("speaker_drift_start_ms", ""), "speaker_drift_end_ms": app.get("speaker_drift_end_ms", ""),
            "headphones_drift_start_ms": app.get("headphones_drift_start_ms", ""), "headphones_drift_end_ms": app.get("headphones_drift_end_ms", ""),
            "codec": after["codec"], "sample_rate": after["sample_rate"], "bits": after["bits"], "delay_report_ms": after["delay_report_ms"],
            "ldac_mode": after["ldac_mode"], "ldac_kbps_before": before["ldac_kbps"], "ldac_kbps_after": after["ldac_kbps"],
            "codec_changed": str(before["codec"] != after["codec"]), "note": a.note,
        }
        fresh = not os.path.exists(OUT)
        with open(OUT, "a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(out))
            if fresh:
                w.writeheader()
            w.writerow(out)
        print(f"{out['result']}: headphones vs speaker {out['later_ms']} ms (negative: heard earlier), codec {out['codec']}, "
              f"delay report {out['delay_report_ms']} ms, LDAC {out['ldac_mode']} {out['ldac_kbps_before']} -> {out['ldac_kbps_after']} kbps.", flush=True)
        if i + 1 < a.runs:
            time.sleep(2)


if __name__ == "__main__":
    main()
