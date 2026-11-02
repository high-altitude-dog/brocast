#!/usr/bin/env python3
"""Tests the music test's analysis on synthetic recordings with known delays. Needs only numpy. Run: python3 measure/test_music.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import beachsync_measure as bm

RATE = 48000
WIN = bm.SOLO_WINDOW_S
GAP = bm.SOLO_SILENCE_S
failures = 0


def music(seconds, seed=1, bass=1.0):
    """Something music-shaped: a steady kick and a bass line, chords that change, and hats, with more energy low down."""
    rng = np.random.default_rng(seed)
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    x = np.zeros(n)
    beat = 60 / 124
    for b in range(int(seconds / beat)):
        i = int(b * beat * RATE)
        k = np.arange(min(int(0.15 * RATE), n - i)) / RATE
        x[i:i + len(k)] += bass * 0.6 * np.exp(-k * 25) * np.sin(2 * np.pi * (50 + 80 * np.exp(-k * 40)) * k)
        note = 55 * 2 ** (rng.integers(0, 12) / 12)
        x[i:i + len(k)] += bass * 0.2 * np.exp(-k * 6) * np.sin(2 * np.pi * note * k)
        for f in (220, 277, 330, 440):
            x[i:i + len(k)] += 0.05 * np.exp(-k * 8) * np.sin(2 * np.pi * f * 2 ** (rng.integers(0, 12) / 12) * k)
        j = i + int(beat / 2 * RATE)
        h = np.arange(min(int(0.04 * RATE), max(0, n - j))) / RATE
        if len(h):
            x[j:j + len(h)] += 0.1 * np.exp(-h * 120) * rng.normal(0, 1, len(h))
    noise = rng.normal(0, 1, n)
    spec = np.fft.rfft(noise) / np.maximum(np.fft.rfftfreq(n, 1 / RATE), 100) ** 0.8
    x += 0.15 * np.fft.irfft(spec, n) / np.std(np.fft.irfft(spec, n))
    return x / np.max(np.abs(x)) * 0.8


def speaker(x, low_cut=250.0, high_cut=14000.0):
    """A phone speaker: no bass and no extreme top."""
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / RATE)
    mask = (f / (f + low_cut)) ** 2 / (1 + (f / high_cut) ** 4)
    return np.fft.irfft(np.fft.rfft(x) * mask, n)


def delayed(x, ms, band_ms=None):
    """x delayed by ms. band_ms adds a delay that changes with frequency, from (hz, ms) points."""
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / RATE)
    total = np.full(len(f), float(ms))
    if band_ms:
        hz, values = zip(*band_ms)
        total = total + np.interp(np.log(np.maximum(f, 1)), np.log(hz), values)
    return np.fft.irfft(np.fft.rfft(x) * np.exp(-2j * np.pi * f * total / 1000), n)


def recording(src, offset_s, seconds, delays, levels=None, bands=None, noise=0.002, echo=0.2, jitter_ms=0.0, seed=3, lead_s=0.5, drive=1.0, start_phase=0.0):
    """delays[i] is phone i's delay in ms, and each phone is heard in its turn. offset_s is where the recording starts in src."""
    rng = np.random.default_rng(seed)
    count = len(delays)
    n = int(seconds * RATE)
    cycle = count * WIN + GAP
    out = np.zeros(n)
    t = np.arange(n) / RATE
    first = int(offset_s * RATE)
    for i, d in enumerate(delays):
        piece = src[first:first + n + RATE]
        played = delayed(speaker(piece), d, bands[i] if bands else None)[:n]
        phase = (t + start_phase) % cycle
        edge = np.minimum(phase - i * WIN, (i + 1) * WIN - phase)
        gain = np.clip(edge / 0.01, 0, 1) * ((phase >= i * WIN) & (phase < (i + 1) * WIN))
        gain = gain * (t >= lead_s)
        wet = played * gain * (levels[i] if levels else 0.3)
        out += wet
        if echo:
            k = int(0.017 * RATE)
            out[k:] += echo * wet[:-k]
    out += rng.normal(0, noise, n)
    return np.clip(out * drive, -1, 1)


def check(name, r, want, tol=0.3, slot=0):
    global failures
    got = None if r is None or r["phones"].get(slot) is None else r["phones"][slot]["delay_ms"]
    ok = got is not None and abs(got - want) < tol
    failures += not ok
    if got is None:
        print(f"  FAIL {name}: nothing found")
    else:
        p = r["phones"][slot]
        print(f"  {'ok  ' if ok else 'FAIL'} {name}: wanted {want:+.2f}, got {got:+.2f}, scatter {p['scatter_ms']:.2f}, {p['turns']} turns, {p['outliers']} outliers, match {r['match']:.2f}")


def main():
    global failures
    print("Music test analysis, from synthetic recordings")
    src = music(60)
    run = lambda rec, count=2, coarse_err=0, offset=5.0: bm.solo_gap(rec, src, RATE, count, int(offset * RATE) + coarse_err)
    check("Galaxy 19 ms behind", run(recording(src, 5, 12, [19, 0])), 19)
    check("Galaxy 19 ms ahead", run(recording(src, 5, 12, [-19, 0])), -19)
    check("in step", run(recording(src, 5, 12, [0, 0])), 0)
    check("0.5 ms apart", run(recording(src, 5, 12, [0.5, 0])), 0.5)
    check("a shared delay of 40 ms cancels out", run(recording(src, 5, 12, [59, 40], echo=0)), 19)
    check("a rough starting point, 20 ms out", run(recording(src, 5, 12, [19, 0]), coarse_err=int(0.02 * RATE)), 19)
    check("recording starts part way through a cycle", run(recording(src, 5, 12, [19, 0], start_phase=0.37)), 19)
    check("phone A is 20 dB quieter", run(recording(src, 5, 12, [19, 0], levels=[0.03, 0.3])), 19)
    check("phone B is 20 dB quieter", run(recording(src, 5, 12, [19, 0], levels=[0.3, 0.03])), 19)
    check("loud room noise", run(recording(src, 5, 12, [19, 0], noise=0.02)), 19, tol=0.5)
    check("only 5 s", run(recording(src, 5, 5, [19, 0])), 19)
    check("clipped recording", run(recording(src, 5, 12, [19, 0], drive=6)), 19, tol=0.5)
    three = run(recording(src, 5, 14, [19, 7, 0]), count=3)
    check("three phones: the first", three, 19, slot=0)
    check("three phones: the second", three, 7, slot=1)
    other = music(60, seed=9)
    r = bm.solo_gap(recording(other, 5, 12, [19, 0]), other, RATE, 2, 5 * RATE)
    check("other music", r, 19)

    # delayed() sets the delay of each frequency on its own, but a band's sound as a whole moves by the group delay:
    # that delay plus its slope against ln(frequency). So 1000 Hz expects 19 - 4 + 1.66 and 4000 Hz expects 19 + 1 + 1.44.
    curve = ((300, -6), (1000, -4), (2000, 0), (4000, 1), (8000, 2))
    r = run(recording(src, 5, 20, [19, 0], bands=[curve, None]))
    print("  by band, for a delay that rises with pitch:", {f: round(v, 1) for f, v in r["phones"][0]["by_band"].items()}, "overall", round(r["phones"][0]["delay_ms"], 1))
    bb = r["phones"][0]["by_band"]
    ok = bb[1000] < bb[4000] and abs(bb[1000] - 16.66) < 1.5 and abs(bb[4000] - 21.44) < 1.5
    failures += not ok
    print(f"  {'ok  ' if ok else 'FAIL'} a delay that changes with pitch shows up in the bands")

    silent = bm.solo_gap(np.random.default_rng(2).normal(0, 0.002, 12 * RATE), src, RATE, 2, 5 * RATE)
    usable = silent is not None and silent["match"] >= 0.2 and silent["phones"][1] is not None
    failures += usable
    print(f"  {'ok  ' if not usable else 'FAIL'} a recording of only room noise gives no answer")
    print("ALL PASSED" if not failures else f"{failures} FAILED")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
