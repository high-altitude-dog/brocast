#!/usr/bin/env python3
"""
Times the camera test card's flashes on two phones filmed side by side (hand-off 31), from one video.

Each flash lasts two of the phone's display frames, shorter than one camera frame, so a flash is timed by the centre of its brightness over the
camera frames, column by column. The camera reads its sensor a line at a time, so a column further across is exposed later: the fit is
    centre(x) = T_phone - k * x
with one readout slope k shared by both phones, which removes the rolling-shutter bias between the left and right phone. The result is when each
phone's flash was actually lit, as the camera saw it.

With the phones' flash logs (testcard-*.csv, from the app's files), it also splits the gap: how late each phone put the flash on a display frame
after the flowtime second (logged), and what's left over, which is the screen's own delay plus any clock difference between the phones.

Usage: testcard_video.py VIDEO [--left-log L.csv --right-log R.csv] [--left-flash-ms 22.2 --right-flash-ms 16.7]
The flash lengths are two display frames of each phone (90 Hz: 22.2 ms, 120 Hz: 16.7 ms); the flash centre is half that after it lights.
"""
import argparse
import csv
import json
import subprocess
import sys

import numpy as np

W, H = 270, 480   # analysis size of the upright frame


def frames(path):
    pts = json.loads(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "json", path],
                                    capture_output=True, text=True, check=True).stdout)["frames"]
    t = np.array([float(f["pts_time"]) for f in pts])
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vf", f"scale={W}:{H}", "-f", "rawvideo", "-pix_fmt", "gray", "-"],
                         capture_output=True, check=True).stdout
    v = np.frombuffer(raw, np.uint8).reshape(-1, H, W).astype(np.float32)
    n = min(len(t), len(v))
    return t[:n], v[:n]


def columns(v):
    """Columns of each phone's screen: pixels that flash (bright peaks far above their usual level), split at the widest gap across x."""
    swing = v.max(0) - np.median(v, 0)
    mask = swing > 0.5 * np.percentile(swing, 99.5)
    count = mask.sum(0)
    # The split: the column near the middle where the fewest pixels flash (the gap between the phones, which only glows).
    mid = int(W * 0.35) + int(np.argmin(count[int(W * 0.35):int(W * 0.65)]))
    strong = count >= 0.3 * count.max()
    left = np.where(strong[:mid])[0]; right = mid + np.where(strong[mid:])[0]
    if len(left) < 10 or len(right) < 10:
        sys.exit("couldn't find two flashing screens")
    return mask, left, right


def events(series, t):
    """Groups of camera frames where a phone's screen is brighter than usual: one per flash."""
    base = np.median(series)
    lit = series > base + 0.25 * (series.max() - base)
    out, cur = [], []
    for i, on in enumerate(lit):
        if on:
            cur.append(i)
        elif cur:
            out.append(cur); cur = []
    if cur:
        out.append(cur)
    return out


def centres(v, t, mask, xs, ev):
    """For one flash and one phone: each column's brightness-weighted time over the flash's frames (and one either side)."""
    lo, hi = max(0, ev[0] - 1), min(len(t), ev[-1] + 2)
    out = []
    for x in xs:
        rows = mask[:, x]
        s = v[:, rows, x].mean(1)
        base = np.median(s[max(0, lo - 12):lo]) if lo > 3 else np.median(s)
        w = np.clip(s[lo:hi] - base, 0, None)
        if w.sum() > 0:
            out.append((x, (w * t[lo:hi]).sum() / w.sum()))
    return out


def refresh(path):
    """The display rate from a log's frame periods, or None for an older log without them."""
    with open(path) as f:
        p = [int(r["frame_period_ns"]) for r in csv.DictReader(f) if r.get("frame_period_ns") and int(r["frame_period_ns"]) > 0]
    return 1e9 / np.median(p) if p else None


def log_offsets(path):
    """Per flash: how long after the flowtime second the app's display frame came (frame flowtime minus event), in ms."""
    with open(path) as f:
        return np.array([(int(r["frame_utc_ns"]) - int(r["event_utc_ns"])) / 1e6 for r in csv.DictReader(f)])


def main():
    p = argparse.ArgumentParser()
    p.add_argument("video")
    p.add_argument("--left-log"); p.add_argument("--right-log")
    p.add_argument("--left-flash-ms", type=float, default=33.3); p.add_argument("--right-flash-ms", type=float, default=16.7)
    a = p.parse_args()
    if a.left_log and refresh(a.left_log):
        a.left_flash_ms = 2000 / refresh(a.left_log)
    if a.right_log and refresh(a.right_log):
        a.right_flash_ms = 2000 / refresh(a.right_log)
    t, v = frames(a.video)
    mask, xl, xr = columns(v)
    sl = v[:, :, xl][:, mask[:, xl]].mean(1); sr = v[:, :, xr][:, mask[:, xr]].mean(1)
    el, er = events(sl, t), events(sr, t)
    print(f"{len(t)} frames, {1 / np.median(np.diff(t)):.1f} fps; left screen {len(xl)} columns, right {len(xr)}; flashes left {len(el)}, right {len(er)}")
    diffs, slopes = [], []
    for e in el:
        tl = t[e].mean()
        match = [f for f in er if abs(t[f].mean() - tl) < 0.5]
        if not match:
            continue
        cl = centres(v, t, mask, xl, e); cr = centres(v, t, mask, xr, match[0])
        if len(cl) < 10 or len(cr) < 10:
            continue
        # One readout slope for both phones: centre = aL*[left] + aR*[right] - k*x.
        rows = [(1, 0, -x, c) for x, c in cl] + [(0, 1, -x, c) for x, c in cr]
        A = np.array([r[:3] for r in rows], float); y = np.array([r[3] for r in rows])
        (aL, aR, k), *_ = np.linalg.lstsq(A, y, rcond=None)
        # Centres to the moment each flash lit: half its length earlier.
        diffs.append(((aR - a.right_flash_ms / 2000) - (aL - a.left_flash_ms / 2000)) * 1000)
        slopes.append(k * W * 1000)
    if not diffs:
        sys.exit("no flash seen on both phones")
    d = np.array(diffs)
    med = np.median(d); keep = np.abs(d - med) < 3 * max(1.0, np.median(np.abs(d - med)) * 1.48)
    print(f"flashes on both: {len(d)} (kept {keep.sum()})")
    print(f"right lit after left: mean {d[keep].mean():+.2f} ms, median {med:+.2f} ms, sd {d[keep].std():.2f} ms, se {d[keep].std() / np.sqrt(keep.sum()):.2f} ms")
    print(f"camera readout across the frame width: {np.median(slopes):.1f} ms")
    if a.left_log and a.right_log:
        ll, lr = log_offsets(a.left_log), log_offsets(a.right_log)
        for name, path in (("left", a.left_log), ("right", a.right_log)):
            hz = refresh(path)
            if hz:
                print(f"{name} display ran at {hz:.0f} Hz (from its log): its flash is {2000 / hz:.1f} ms")
        print(f"logged frame after the second: left {ll.mean():.2f} ms (sd {ll.std():.2f}), right {lr.mean():.2f} ms (sd {lr.std():.2f})")
        rest = d[keep].mean() - (lr.mean() - ll.mean())
        print(f"left over (right minus left): {rest:+.2f} ms = screen delay difference + clock difference")


if __name__ == "__main__":
    main()
