#!/usr/bin/env python3
"""beachsync_measure: how far apart are two phones? Reads a WAV recording and reports the gaps.

Three ways to record (on Debian: arecord, see the guide):

  ticks-stereo  phones in tick mode, phone A on the LEFT input, phone B on the RIGHT input
  ticks-tones   phones in tick mode through their speakers, phone A clicking at --fa Hz and
                phone B at --fb Hz, both the same distance from one microphone (mono or stereo)
  music-stereo  phones in stream mode playing the same stream, A on LEFT, B on RIGHT

Gap = time phone B is heard minus time phone A is heard, in milliseconds (positive = B late).

    python3 beachsync_measure.py ticks-stereo rec.wav
    python3 beachsync_measure.py ticks-tones rec.wav --fa 1000 --fb 3000
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
    p.add_argument("mode", choices=["ticks-stereo", "ticks-tones", "music-stereo"])
    p.add_argument("wav")
    p.add_argument("--fa", type=float, default=1000.0, help="phone A click pitch, Hz (ticks-tones)")
    p.add_argument("--fb", type=float, default=3000.0, help="phone B click pitch, Hz (ticks-tones)")
    p.add_argument("--extra-b-cm", type=float, default=0.0,
                   help="ticks-tones: how much further phone B is from the mic than A, in cm (corrected for)")
    p.add_argument("--csv", help="write each gap to this CSV file")
    a = p.parse_args()
    x, rate = read_wav(a.wav)
    if a.mode in ("ticks-stereo", "music-stereo") and x.shape[0] < 2:
        sys.exit("this mode needs a stereo recording: phone A on left, phone B on right")
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
