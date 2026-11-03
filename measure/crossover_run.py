#!/usr/bin/env python3
"""The crossover test: the acoustic two-phone test with one phone on Bluetooth headphones and the other on its speaker.

    python3 measure/crossover_run.py --label "Galaxy headphones, Pixel speaker" --runs 3 --a R5CX93ZA1TN --a-route headphones --b 5A161FDCH00197 --b-route speaker

Phone A and phone B are the same as in quicktest.py. Both phones must already be running and locked to GPS (quicktest.py --prepare).
Put the headphones' ear cup next to the laptop's microphone, at about the distance of the other phone's speaker. Each run is a normal
quicktest run (10 s countdown, about 10 s of ticks, silence) and then one row is added to measure/crossover-results.csv, which git ignores,
with the Bluetooth codec and the headset's reported delay read just before and after. A positive gap means phone B is later.
"""
import argparse
import csv
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import earcup_run as e  # noqa: E402

OUT = os.path.join(HERE, "crossover-results.csv")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--label", required=True)
    p.add_argument("--a", required=True)
    p.add_argument("--b", required=True)
    p.add_argument("--a-route", default="speaker", choices=["speaker", "headphones"])
    p.add_argument("--b-route", default="speaker", choices=["speaker", "headphones"])
    p.add_argument("--runs", type=int, default=1)
    p.add_argument("--note", default="")
    a = p.parse_args()
    bt = a.a if a.a_route == "headphones" else (a.b if a.b_route == "headphones" else None)

    for i in range(a.runs):
        before = e.bluetooth(bt) if bt else {}
        print(f"Run {i + 1} of {a.runs}: {a.label}. Codec {before.get('codec', '-')}, delay report {before.get('delay_report_ms', '-')} ms.", flush=True)
        r = subprocess.run([sys.executable, os.path.join(HERE, "quicktest.py"), "--a", a.a, "--b", a.b], capture_output=True, text=True)
        sys.stdout.write(r.stdout)
        after = e.bluetooth(bt) if bt else {}
        gap = re.search(r"GAP\s+(-?[\d.]+)\s*ms.*?scatter\s+([\d.]+)\s*ms", r.stdout)
        warns = [ln for ln in r.stdout.splitlines() if ln.startswith("WARNING")]
        if not gap:
            print("No result in this run; nothing logged.", flush=True)
            continue
        row = {
            "time": __import__("time").strftime("%Y-%m-%d %H:%M:%S"), "label": a.label,
            "a_route": a.a_route, "b_route": a.b_route, "gap_ms_b_later_positive": gap.group(1), "scatter_ms": gap.group(2),
            "codec_before": before.get("codec", ""), "codec_after": after.get("codec", ""), "delay_report_ms": after.get("delay_report_ms", ""),
            "ldac_mode": after.get("ldac_mode", ""), "ldac_kbps_before": before.get("ldac_kbps", ""), "ldac_kbps_after": after.get("ldac_kbps", ""),
            "warnings": len(warns), "note": a.note,
        }
        fresh = not os.path.exists(OUT)
        with open(OUT, "a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(row))
            if fresh:
                w.writeheader()
            w.writerow(row)
        print(f"Logged: gap {row['gap_ms_b_later_positive']} ms (B later positive), codec {row['codec_before']} -> {row['codec_after']}, warnings {row['warnings']}.", flush=True)


if __name__ == "__main__":
    main()
