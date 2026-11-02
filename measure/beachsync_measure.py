#!/usr/bin/env python3
"""beachsync_measure: how far apart are two phones? Reads a WAV recording and reports the gaps.

Three ways to record (on Debian: arecord, see the guide):

  ticks-stereo  phones in tick mode, phone A on the LEFT input, phone B on the RIGHT input
  ticks-tones   phones in tick mode through their speakers, phone A clicking at --fa Hz and
                phone B at --fb Hz, both the same distance from one microphone (mono or stereo)
  ticks-pattern both phones in the tick pattern through their speakers: one pitch at a time, phone B 60 ms after phone A,
                stepping through five pitches, which gives the gap at each pitch (see quicktest.py)
  music-stereo  phones in stream mode playing the same stream, A on LEFT, B on RIGHT

Gap = time phone B is heard minus time phone A is heard, in milliseconds (positive = B late).

    python3 beachsync_measure.py ticks-stereo rec.wav
    python3 beachsync_measure.py ticks-tones rec.wav --fa 1000 --fb 2500
    python3 beachsync_measure.py music-stereo rec.wav --csv gaps.csv

Needs Python 3.8+ and numpy (sudo apt install python3-numpy).
"""
from __future__ import annotations

import argparse
import sys
import wave

import numpy as np


def read_wav(path):
    with wave.open(path, "rb") as w:
        ch, width, rate, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if width == 2:
        x = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768
    elif width == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
        v = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        v = np.where(v & 0x800000, v - 0x1000000, v)
        x = v.astype(np.float64) / 8388608
    elif width == 4:
        x = np.frombuffer(raw, dtype="<i4").astype(np.float64) / 2147483648
    else:
        sys.exit(f"unsupported sample width {width}")
    return x.reshape(-1, ch).T, rate


def bandpass(x, rate, f, width):
    """Zero-phase FFT band-pass: keeps f +/- width Hz. Zero phase means no added delay."""
    n = len(x)
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, 1 / rate)
    mask = np.exp(-0.5 * ((freqs - f) / width) ** 2)
    return np.fft.irfft(spec * mask, n)


def envelope(x, rate, smooth_ms=0.3):
    k = max(1, int(rate * smooth_ms / 1000))
    return np.convolve(np.abs(x), np.ones(k) / k, mode="same")


def onsets(x, rate, refractory_s=0.5, rel_threshold=0.3):
    """Times (s) where the envelope first rises through rel_threshold of the local peak level,
    refined to a fraction of a sample by linear interpolation. Clicks must be >= refractory apart."""
    env = envelope(x, rate)
    if env.max() <= 0:
        return np.array([])
    level = np.percentile(env, 99.9)
    thr = rel_threshold * level
    above = env > thr
    starts = np.flatnonzero(above[1:] & ~above[:-1]) + 1
    out, last = [], -1e9
    for i in starts:
        if (i - last) / rate < refractory_s:
            continue
        last = i
        e0, e1 = env[i - 1], env[i]
        frac = (thr - e0) / (e1 - e0) if e1 != e0 else 0.0
        out.append((i - 1 + frac) / rate)
    return np.array(out)


def pair(ta, tb, max_gap_s=0.25):
    gaps, times = [], []
    j = 0
    for t in ta:
        while j < len(tb) - 1 and abs(tb[j + 1] - t) < abs(tb[j] - t):
            j += 1
        if len(tb) and abs(tb[j] - t) <= max_gap_s:
            gaps.append((tb[j] - t) * 1000)
            times.append(t)
    return np.array(times), np.array(gaps)


def music_gaps(a, b, rate, win_s=2.0, step_s=5.0, max_lag_ms=250):
    win, step, maxlag = int(win_s * rate), int(step_s * rate), int(max_lag_ms * rate / 1000)
    times, gaps = [], []
    for s in range(maxlag, len(a) - win - maxlag, step):
        x = a[s:s + win]
        y = b[s - maxlag:s + win + maxlag]
        if np.std(x) < 1e-4 or np.std(y) < 1e-4:
            continue
        nfft = 1 << int(np.ceil(np.log2(len(x) + len(y))))
        c = np.fft.irfft(np.fft.rfft(y, nfft) * np.conj(np.fft.rfft(x, nfft)), nfft)[:2 * maxlag + 1]
        k = int(np.argmax(c))
        if 0 < k < len(c) - 1:  # parabolic peak for sub-sample precision
            y0, y1, y2 = c[k - 1], c[k], c[k + 1]
            d = 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2) if (y0 - 2 * y1 + y2) != 0 else 0.0
        else:
            d = 0.0
        lag = k + d - maxlag
        peak = c[k] / (np.linalg.norm(x) * np.linalg.norm(y[maxlag:maxlag + win]) + 1e-12)
        if peak < 0.3:  # weak match: skip rather than report noise
            continue
        times.append(s / rate)
        gaps.append(lag / rate * 1000)
    return np.array(times), np.array(gaps)


PATTERN_HZ = (500.0, 1000.0, 2000.0, 4000.0, 8000.0)    # as Renderer.PATTERN_HZ
PATTERN_SLOT_S = 0.2
PATTERN_STAGGER_S = 0.06


def pitch_response(x, rate, f):
    """How strongly the recording matches a 5 ms click at pitch f, at each moment. Matching the click rather than looking
    for a rise in level means a sound at another pitch barely registers. The smooth window also ignores sharp edges."""
    k = np.arange(int(0.005 * rate)) / rate
    template = np.hanning(len(k)) * np.exp(2j * np.pi * f * k)
    size = len(x) + len(k)
    return np.abs(np.fft.ifft(np.fft.fft(x, size) * np.conj(np.fft.fft(template, size))))[:len(x)]


def refine(win, i):
    if i <= 0 or i >= len(win) - 1:
        return float(i)
    a, b, c = win[i - 1], win[i], win[i + 1]
    return i + (0.5 * (a - c) / (a - 2 * b + c) if (a - 2 * b + c) != 0 else 0.0)


def pattern_gap(mono, rate, pitches=PATTERN_HZ, slot_s=PATTERN_SLOT_S, stagger_s=PATTERN_STAGGER_S, max_gap_ms=30.0):
    """The tick pattern (Renderer.patternTicks). Each slot holds two clicks at one pitch: phone A's, then phone B's stagger_s
    later. So the gap is how far the spacing differs from stagger_s, and the order says which phone is which at any gap
    under max_gap_ms. Stepping through the pitches shows whether the gap depends on pitch."""
    slot = int(round(slot_s * rate))
    cycle = slot * len(pitches)
    ys = [pitch_response(mono, rate, f) for f in pitches]
    if len(mono) < 2 * cycle:
        return None
    # Find where the cycle starts: the offset where every pitch's clicks line up with its own slot.
    box = np.ones(int(0.02 * rate))
    score = np.zeros(cycle)
    for k, y in enumerate(ys):
        fold = np.bincount(np.arange(len(y)) % cycle, weights=y, minlength=cycle)
        fold = fold / (fold.max() or 1.0)
        smooth = np.convolve(np.r_[fold, fold[:len(box)]], box, mode="same")[:cycle]
        score += np.roll(smooth, -k * slot)
    centre = int(np.argmax(score))
    # The other click can only be this far from the stronger one. Looking nowhere else keeps early echoes out, because a
    # loud phone's echo can be stronger than a quiet phone's click.
    near = int((stagger_s - max_gap_ms / 1000) * rate)
    far = int((stagger_s + max_gap_ms / 1000) * rate)
    rows = []
    for k, y in enumerate(ys):
        floor = np.median(y)
        start = (centre + k * slot - slot // 2) % cycle
        for s in range(start, len(y) - slot + 1, cycle):
            win = y[s:s + slot]
            i = int(np.argmax(win))
            if win[i] < 8 * floor or win[i] <= 0:    # 8 times, so silence before the ticks isn't a click
                continue
            rest = np.zeros_like(win)
            rest[max(0, i - far):max(0, i - near)] = win[max(0, i - far):max(0, i - near)]
            rest[i + near:i + far] = win[i + near:i + far]
            j = int(np.argmax(rest))
            if rest[j] < 8 * floor:
                continue
            t1, t2 = sorted((refine(win, i), refine(win, j)))
            gap = ((t2 - t1) / rate - stagger_s) * 1000
            rows.append((k, gap, (s + t1) / rate, (s + t2) / rate, 20 * np.log10(win[i] / rest[j])))
    if len(rows) < 5:
        return None
    pitch = np.array([r[0] for r in rows])
    gaps = np.array([r[1] for r in rows])
    per_pitch, resid = {}, np.zeros(len(gaps))
    for k, f in enumerate(pitches):
        if np.any(pitch == k):
            med = float(np.median(gaps[pitch == k]))
            per_pitch[int(f)] = {"gap_ms": med, "clicks": int(np.sum(pitch == k))}
            resid[pitch == k] = gaps[pitch == k] - med
    return {"gap_ms": float(np.median(gaps)), "per_pitch": per_pitch, "scatter_ms": float(np.median(np.abs(resid))),
            "clicks": len(gaps), "outliers": int((np.abs(resid) > 2).sum()),
            "level_diff_db": float(np.median([r[4] for r in rows])), "times": [t for r in rows for t in r[2:4]]}


SOLO_WINDOW_S = 0.4         # as Renderer.SOLO_WINDOW_NS
SOLO_SILENCE_S = 0.2        # as Renderer.SOLO_SILENCE_NS
SOLO_BANDS = (500.0, 1000.0, 2000.0, 4000.0, 8000.0)


def cross_lag(chunk, src, rate):
    """Where in src this chunk of recording best fits, in samples from the start of src, and how well, from 0 to 1."""
    if len(src) < len(chunk):
        return None
    size = len(src) + len(chunk)
    c = np.fft.irfft(np.fft.rfft(src, size) * np.conj(np.fft.rfft(chunk, size)), size)[:len(src) - len(chunk) + 1]
    i = int(np.argmax(c))
    energy = np.sqrt(np.sum(chunk ** 2) * np.sum(src[i:i + len(chunk)] ** 2))
    return refine(c, i), (float(c[i] / energy) if energy > 0 else 0.0)


def find_turns(rec, rate, count, window_s=SOLO_WINDOW_S, silence_s=SOLO_SILENCE_S):
    """When turn 0 of a cycle starts, from the silence between cycles. Times are in seconds, modulo the cycle."""
    cycle = int(round((count * window_s + silence_s) * rate))
    power = np.bincount(np.arange(len(rec)) % cycle, weights=rec ** 2, minlength=cycle)
    gap = int(silence_s * rate)
    wrapped = np.cumsum(np.r_[power, power[:gap]])
    quiet = wrapped[gap:] - wrapped[:-gap]
    return (int(np.argmin(quiet)) + gap) % cycle, cycle


def solo_lags(rec, src, rate, count, coarse, margin_s=0.05, window_s=SOLO_WINDOW_S, silence_s=SOLO_SILENCE_S):
    """Where each phone's turns fit in src. coarse is roughly where sample 0 of rec falls in src. Returns one row per turn:
    (slot, turn's start in rec, lag in samples from coarse, match quality)."""
    start, cycle = find_turns(rec, rate, count, window_s, silence_s)
    win = int(window_s * rate)
    edge = int(0.03 * rate)     # keep clear of the fades, and of the phones being a little early or late
    margin = int(margin_s * rate)
    rows = []
    for c0 in range(start - cycle, len(rec), cycle):
        for slot in range(count):
            a = c0 + slot * win + edge
            b = c0 + (slot + 1) * win - edge
            if a < 0 or b > len(rec):
                continue
            s0 = int(coarse) + a - margin
            s1 = int(coarse) + b + margin
            if s0 < 0 or s1 > len(src):
                continue
            chunk = rec[a:b]
            if np.max(np.abs(chunk)) <= 0:
                continue
            found = cross_lag(chunk, src[s0:s1], rate)
            if found:
                # A phone that plays late puts a given sound later in the recording, so it fits earlier in src.
                rows.append((slot, a, margin + (coarse - int(coarse)) - found[0], found[1]))
    return rows


def refine_coarse(rec, src, rate, coarse, span_s=2.0):
    """A better starting point from the whole recording, which allows for any delay shared by all the phones."""
    span = int(span_s * rate)
    s0 = max(0, int(coarse) - span)
    s1 = min(len(src), int(coarse) + len(rec) + span)
    found = cross_lag(rec, src[s0:s1], rate)
    return s0 + found[0] if found else coarse


def solo_gap(rec, src, rate, count, coarse, reference=None, min_match=0.2, bands=SOLO_BANDS):
    """The music test. Each phone's delay against a reference phone (default the last slot), in ms, overall and in each band.
    Positive means that phone is heard later than the reference. A small delay in one phone shifts where its turns fit in src."""
    coarse = refine_coarse(rec, src, rate, coarse)

    def table(r, s):
        rows = solo_lags(r, s, rate, count, coarse)
        out = {}
        for slot in range(count):
            lags = np.array([x[2] for x in rows if x[0] == slot and x[3] >= min_match])
            out[slot] = (float(np.median(lags)) / rate * 1000, lags / rate * 1000) if len(lags) else None
        return out, rows

    ref = count - 1 if reference is None else reference
    whole, rows = table(rec, src)
    if whole[ref] is None:
        return None
    phones = {}
    for slot in range(count):
        if whole[slot] is None:
            phones[slot] = None
            continue
        med, lags = whole[slot]
        phones[slot] = {"delay_ms": med - whole[ref][0], "turns": len(lags), "scatter_ms": float(np.median(np.abs(lags - med))),
                        "outliers": int((np.abs(lags - med) > 2).sum()), "by_band": {}}
    weights = {}
    for f in bands:
        if f * 1.4 >= rate / 2:
            continue
        heard = bandpass(rec, rate, f, f / 3)
        bandwise, band_rows = table(heard, bandpass(src, rate, f, f / 3))
        if bandwise[ref] is None:
            continue
        weights[int(f)] = float(np.sum(heard ** 2))
        if f == 2000.0:
            rows = band_rows    # the steadiest band for the by-set figures: phone speakers play it well
            for slot, p in phones.items():
                if p is not None and bandwise[slot] is not None:
                    lags = bandwise[slot][1]
                    p.update(turns=len(lags), scatter_ms=float(np.median(np.abs(lags - bandwise[slot][0]))),
                             outliers=int((np.abs(lags - bandwise[slot][0]) > 2).sum()))
        for slot, p in phones.items():
            if p is not None and bandwise[slot] is not None:
                p["by_band"][int(f)] = bandwise[slot][0] - bandwise[ref][0]
    # The full-range match is led by bass that phone speakers barely play, and a speaker shifts the phase badly near its
    # low cut-off, so it can even get the sign wrong. So the overall delay is the bands, weighted by how much of each was heard.
    for p in phones.values():
        if p is not None and p["by_band"]:
            total = sum(weights[f] for f in p["by_band"])
            p["full_range_ms"] = p["delay_ms"]
            p["delay_ms"] = sum(weights[f] * d for f, d in p["by_band"].items()) / total
    quality = [x[3] for x in rows]
    return {"phones": phones, "match": float(np.median(quality)) if quality else 0.0, "rows": rows, "coarse": coarse}


def report(times, gaps, csv_path=None, label=""):
    if len(gaps) == 0:
        sys.exit("no matching events found: check levels, channels and mode")
    med = np.median(gaps)
    print(f"{label}{len(gaps)} gaps over {times[-1] - times[0]:.0f} s   (positive = phone B later)")
    print(f"  mean     {np.mean(gaps):+8.3f} ms")
    print(f"  median   {med:+8.3f} ms")
    print(f"  std dev  {np.std(gaps, ddof=1) if len(gaps) > 1 else 0:8.3f} ms")
    print(f"  95% of |gap| under {np.percentile(np.abs(gaps), 95):.3f} ms")
    print(f"  worst    {gaps[np.argmax(np.abs(gaps))]:+8.3f} ms")
    if csv_path:
        with open(csv_path, "w") as f:
            f.write("time_s,gap_ms\n")
            for t, g in zip(times, gaps):
                f.write(f"{t:.4f},{g:.4f}\n")
        print(f"  per-event gaps written to {csv_path}")


def main():
    p = argparse.ArgumentParser(description="Measure the timing gap between two phones from a WAV recording.")
    p.add_argument("mode", choices=["ticks-stereo", "ticks-tones", "ticks-pattern", "music-stereo"])
    p.add_argument("wav")
    p.add_argument("--fa", type=float, default=1000.0, help="phone A click pitch, Hz (ticks-tones)")
    p.add_argument("--fb", type=float, default=2500.0, help="phone B click pitch, Hz (ticks-tones); avoid harmonics of --fa")
    p.add_argument("--extra-b-cm", type=float, default=0.0,
                   help="ticks-tones: how much further phone B is from the mic than A, in cm (corrected for)")
    p.add_argument("--csv", help="write each gap to this CSV file")
    a = p.parse_args()
    x, rate = read_wav(a.wav)
    if a.mode in ("ticks-stereo", "music-stereo") and x.shape[0] < 2:
        sys.exit("this mode needs a stereo recording: phone A on left, phone B on right")
    if a.mode == "ticks-pattern":
        r = pattern_gap(x.mean(axis=0), rate)
        if r is None:
            sys.exit("too few clicks found: check that both phones are playing and audible")
        shift = a.extra_b_cm / 100 / 343.0 * 1000
        print(f"[ticks-pattern] gap {r['gap_ms'] - shift:+.2f} ms (positive = phone B later), scatter {r['scatter_ms']:.2f} ms, "
              f"{r['clicks']} pairs, {r['outliers']} outliers")
        for f, v in r["per_pitch"].items():
            print(f"  {f:5d} Hz  {v['gap_ms'] - shift:+7.2f} ms  ({v['clicks']} pairs)")
        return
    if a.mode == "ticks-stereo":
        times, gaps = pair(onsets(x[0], rate), onsets(x[1], rate))
    elif a.mode == "ticks-tones":
        mono = x.mean(axis=0)
        width = min(a.fa, a.fb, abs(a.fb - a.fa)) / 4
        ta = onsets(bandpass(mono, rate, a.fa, width), rate)
        tb = onsets(bandpass(mono, rate, a.fb, width), rate)
        times, gaps = pair(ta, tb)
        gaps = gaps - a.extra_b_cm / 100 / 343.0 * 1000  # sound travels about 343 m/s
    else:
        times, gaps = music_gaps(x[0], x[1], rate)
    report(times, gaps, a.csv, f"[{a.mode}] ")


if __name__ == "__main__":
    main()
