#!/usr/bin/env python3
"""Tests the tick pattern's analysis on synthetic recordings with known gaps. Needs only numpy. Run: python3 measure/test_pattern.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import beachsync_measure as bm

RATE = 48000
PITCHES = bm.PATTERN_HZ
SLOT = bm.PATTERN_SLOT_S
STAGGER = bm.PATTERN_STAGGER_S
failures = 0


def click(hz, level):
    n = int(0.005 * RATE)
    t = np.arange(n) / RATE
    env = np.minimum(1, t / 0.0002) * (0.5 + 0.5 * np.cos(np.pi * t / 0.005))
    return level * env * np.sin(2 * np.pi * hz * t)


def put(x, at_s, burst, harmonics=0.0, hz=0.0, level=0.0):
    for delay_ms, share in ((0, 1.0), (12, 0.25), (27, 0.12)):    # the click and a little room
        i = int(round((at_s + delay_ms / 1000) * RATE))
        x[i:i + len(burst)] += share * burst
    if harmonics:
        i = int(round(at_s * RATE))
        h = click(2 * hz, level * harmonics)
        x[i:i + len(h)] += h


def recording(gap_ms, seconds=10.0, level_a=0.1, level_b=0.4, jitter_ms=0.1, noise=0.002, first=0, drive=1.0, seed=1, lead_s=0.5,
              by_hz=None, weak_bass=False, harmonics=0.0):
    """Phone B plays gap_ms after where it should, and by_hz adds to that at particular pitches."""
    rng = np.random.default_rng(seed)
    x = np.zeros(int((seconds + lead_s + 1) * RATE))
    for k in range(int(seconds / SLOT)):
        hz = PITCHES[(k + first) % len(PITCHES)]
        t = lead_s + k * SLOT
        shade = 0.1 if weak_bass and hz == 500 else 1.0
        extra = (by_hz or {}).get(hz, 0.0)
        put(x, t + rng.normal(0, jitter_ms) / 1000, click(hz, level_a * shade), harmonics, hz, level_a)
        put(x, t + STAGGER + (gap_ms + extra + rng.normal(0, jitter_ms)) / 1000, click(hz, level_b * shade), harmonics, hz, level_b)
    x += rng.normal(0, noise, len(x))
    return np.clip(x * drive, -1, 1)


def check(name, r, gap, tol=0.15):
    global failures
    ok = r is not None and abs(r["gap_ms"] - gap) < tol
    failures += not ok
    shown = "nothing found" if r is None else f"gap {r['gap_ms']:+.2f}, scatter {r['scatter_ms']:.2f}, {r['clicks']} pairs, {r['outliers']} outliers"
    print(f"  {'ok  ' if ok else 'FAIL'} {name}: wanted {gap:+.2f}, got {shown}")


def main():
    global failures
    print("Tick pattern analysis, from 10 s of synthetic recording")
    an = lambda x: bm.pattern_gap(x, RATE)
    check("B 18 ms later", an(recording(18)), 18)
    check("A 18 ms later: the sign must flip", an(recording(-18)), -18)
    check("in step", an(recording(0)), 0)
    check("B later by 0.3 ms: the sign must hold when nearly in step", an(recording(0.3)), 0.3)
    check("A later by 0.3 ms", an(recording(-0.3)), -0.3)
    for first in (1, 3):
        check(f"recording starts at pitch {first} of the cycle", an(recording(18, first=first)), 18)
    check("phone A 20 dB quieter", an(recording(18, level_a=0.03, level_b=0.3)), 18)
    check("phone B 20 dB quieter", an(recording(18, level_a=0.3, level_b=0.03)), 18)
    check("no bass from the speakers", an(recording(18, weak_bass=True)), 18)
    check("strong second harmonics", an(recording(18, harmonics=0.8)), 18)
    check("near the 30 ms limit", an(recording(25)), 25)
    check("near the limit the other way", an(recording(-25)), -25)
    check("clipped recording", an(recording(18, drive=8)), 18, tol=0.5)
    r = an(recording(18, level_a=0.03, level_b=0.3))
    ok = r is not None and abs(r["level_diff_db"] - 20) < 3
    failures += not ok
    print(f"  {'ok  ' if ok else 'FAIL'} the level difference between the phones is reported:", round(r["level_diff_db"], 1) if r else None)
    check("loud room noise", an(recording(18, noise=0.01)), 18)
    for lead in (0.63, 1.31):
        check(f"recording starts {lead} s before the ticks", an(recording(18, lead_s=lead)), 18)
    check("only 5 s of ticks", an(recording(18, seconds=5)), 18)

    r = an(recording(10, by_hz={500: 6.0, 8000: -4.0}))
    want = {500: 16, 1000: 10, 2000: 10, 4000: 10, 8000: 6}
    ok = r is not None and all(abs(r["per_pitch"][f]["gap_ms"] - w) < 0.2 for f, w in want.items())
    failures += not ok
    print(f"  {'ok  ' if ok else 'FAIL'} a gap that changes with pitch is seen at each pitch:", {f: round(v["gap_ms"], 2) for f, v in r["per_pitch"].items()} if r else None)

    silent = an(np.random.default_rng(2).normal(0, 0.002, 5 * RATE))
    failures += silent is not None
    print(f"  {'ok  ' if silent is None else 'FAIL'} a recording with no clicks gives no answer")
    print("ALL PASSED" if not failures else f"{failures} FAILED")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
