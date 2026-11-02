#!/usr/bin/env python3
"""The music test: do phones with different hardware hear real music together? The phones make no sound except during the test.

    python3 measure/musictest.py --prepare       # silent: start the phones, buffer, wait for GPS, then exit
    python3 measure/musictest.py                 # 10 s countdown, recording starts, the music, then silence and the result
    python3 measure/musictest.py --lag 19        # the same with 19 ms of headphone lag on the first phone
    python3 measure/musictest.py --finish        # end the runs when you're done

Every phone plays the same stream through the whole app, and they take turns to be heard: 400 ms each, then 200 ms of
silence, which marks where the turns start. Only one phone sounds at a time, so the recording can be matched against the
stream's own audio to find each phone's delay, on music rather than on clicks. It reports each phone against the reference.

The reference is the last phone, which is the Pixel unless you pass --serials. If every phone matches it, listeners hear
each other together, whatever delay they all share. Proving absolute flowtime would need a separate reference.

Put the phones either side of the centre of the laptop's screen, level with each other: the two microphones in the bezel
are averaged, which cancels the difference in distance. Use a server playing the test reel (server/make_reel.py) to cover
every set, and keep --seconds at 50 or more so that a run hears all of it.

Needs adb, arecord, amixer and numpy. Recordings are deleted once analysed, and each result is added to music-results.csv.
"""
import argparse
import csv
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import beachsync_measure as bm  # noqa: E402
import quicktest as qt  # noqa: E402

RATE = 48000
TARGET_MS = 5.0     # the spread that counts as in sync on music
READY_TIMEOUT_S = 240
HOLD_S = 4
REEL_SET_S = 10     # server/make_reel.py: seconds of each set
REEL_SETS = 5
GAIN_FILE = os.path.join(HERE, "gain-music.txt")    # separate from the tick test's, which needs a different level


def phones_in_order(serials):
    """The reference last, as solo_gap expects."""
    if serials:
        return serials.split(",")
    a, b = qt.find_phones(None, None)
    return [a, b]


def name_of(serial):
    return qt.adb(serial, "shell", "getprop", "ro.product.model").strip() or serial


def server_url(serial):
    xml = qt.adb(serial, "shell", "run-as org.beachsync.proto cat shared_prefs/proto.xml 2>/dev/null")
    m = re.search(r'<string name="url">([^<]+)</string>', xml)
    return m.group(1).rstrip("/") if m else None


def is_running(serial, slot, count, lag):
    info = qt.adb(serial, "shell", f"cat {qt.FILES}/{qt.latest(serial, 'info')}")
    if f"solo {slot} of {count}" not in info or f"headphone lag: {float(lag)} ms" not in info or "both channel" not in info:
        return False
    age = qt.adb(serial, "shell", f"cd {qt.FILES}; echo $(( $(date +%s) - $(stat -c %Y {qt.latest(serial, 'status')}) ))").strip()
    return age.isdigit() and int(age) < 5 and "isForeground=true" in qt.adb(serial, "shell", "dumpsys", "activity", "services", "org.beachsync.proto")


def restart(serial, slot, count, lag):
    qt.adb(serial, "shell", "am", "force-stop", "org.beachsync.proto")
    path = "shared_prefs/proto.xml"
    xml = qt.adb(serial, "shell", f"run-as org.beachsync.proto cat {path} 2>/dev/null")
    if "<map" not in xml:
        xml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>'
    for kind, key, value in (("boolean", "stream", "true"), ("string", "channel", "BOTH"), ("string", "lag", lag), ("boolean", "satellites", "true")):
        xml = qt.setting(xml, kind, key, value)
    qt.adb(serial, "shell", f"run-as org.beachsync.proto sh -c 'mkdir -p shared_prefs; cat > {path}'", stdin=xml)
    qt.adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    qt.adb(serial, "shell", "wm", "dismiss-keyguard")
    qt.adb(serial, "shell", "am", "start", "-W", "-n", "org.beachsync.proto/.MainActivity", "--ei", "soloSlot", str(slot), "--ei", "soloCount", str(count))
    for _ in range(10):
        qt.adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
        m = re.search(r'text="START RUN"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', qt.adb(serial, "shell", "cat", "/sdcard/ui.xml"), re.I)
        if m:
            x0, y0, x1, y1 = map(int, m.groups())
            qt.adb(serial, "shell", "input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))
            return
        time.sleep(0.5)
    sys.exit(f"{serial}: couldn't find the Start button")


def status(serial):
    r = qt.adb(serial, "shell", f"cd {qt.FILES}; tail -n 1 $(ls -t | grep -- '-status' | head -1)").strip().split(",")
    if len(r) < 27:
        return None
    d = dict(src=r[2], err=float(r[3] or 1e12) / 1e6, sats=int(r[7] or 0), age=float(r[11] or 1e9), under=int(r[19] or 0),
             missing=int(r[20] or 0), jumps=int(r[18] or 0), held=float(r[24] or 0) / 1e6, buffered=int(r[26] or 0))
    return d


def ready(d):
    return bool(d) and qt.locked(d) and d["buffered"] >= 10


def describe(d):
    return f"{qt.describe(d)}, {d['buffered']} s buffered, correction left {d['held']:.2f} ms" if d else "not logging yet"


def prepare(serials, lags):
    count = len(serials)
    restarted = []
    for slot, serial in enumerate(serials):
        if not is_running(serial, slot, count, lags[slot]):
            restart(serial, slot, count, lags[slot])
            restarted.append(name_of(serial))
    start = time.time()
    since = None
    last = {}
    while True:
        states = {s: status(s) for s in serials}
        last = {s: (d["missing"] if d else None) for s, d in states.items()} if since is None else last
        if all(ready(d) for d in states.values()):
            since = since or time.time()
            still = all(states[s]["missing"] == last[s] for s in serials)
            if not still:
                since = None
            elif time.time() - since >= HOLD_S:
                break
        else:
            since = None
        if time.time() - start > READY_TIMEOUT_S:
            lines = "\n".join(f"  {name_of(s)}: {describe(d)}" for s, d in states.items())
            sys.exit(f"not ready after {READY_TIMEOUT_S} s, so no test ran.\n{lines}")
        time.sleep(1)
    print(f"Ready and silent: {'restarted ' + ' and '.join(restarted) if restarted else 'phones were already running'}, took {time.time() - start:.0f} s.", flush=True)
    for s, d in states.items():
        print(f"  {name_of(s)}: {describe(d)}", flush=True)
    return states


def manifest(url):
    t0 = time.time()
    with urllib.request.urlopen(url + "/manifest.json", timeout=5) as r:
        m = json.load(r)
    return m, (m["server_utc_ns"] / 1e9) - (t0 + time.time()) / 2


def fetch_source(url, first, last, loop_seconds):
    """The stream's own audio as one mono track, from segment first to segment last. It downloads the compressed file and
    decodes it here. The decoder removes Opus's fixed delay, so the phones sit 6.5 ms later than this: the same for every phone."""
    with tempfile.TemporaryDirectory() as folder:
        path = os.path.join(folder, "on_air.opus")
        urllib.request.urlretrieve(f"{url}/on_air.opus", path)
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"], check=True, capture_output=True).stdout
    x = np.frombuffer(raw, dtype="<f4").astype(np.float64)
    loop = np.zeros(loop_seconds * RATE)
    loop[:min(len(x), len(loop))] = x[:len(loop)]
    return np.take(loop, np.arange(first * RATE, (last + 1) * RATE), mode="wrap")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--prepare", action="store_true", help="only start the phones, buffer and wait for GPS, silently")
    p.add_argument("--finish", action="store_true", help="end the runs on all the phones")
    p.add_argument("--seconds", type=int, default=50, help="how long the music lasts")
    p.add_argument("--no-countdown", action="store_true")
    p.add_argument("--lag", type=float, default=0.0, help="headphone lag on the first phone, ms; a larger lag plays earlier")
    p.add_argument("--serials", help="adb serials, comma-separated, with the reference last")
    args = p.parse_args()

    serials = phones_in_order(args.serials)
    if args.finish:
        for s in serials:
            qt.adb(s, "shell", "am", "force-stop", "org.beachsync.proto")
        print("Runs ended on all the phones.")
        return
    count = len(serials)
    lags = [args.lag] + [0.0] * (count - 1)
    url = server_url(serials[0])
    if not url:
        sys.exit("no server address saved on the first phone: set it in the app once")

    before = prepare(serials, lags)
    if args.prepare:
        return

    m, server_ahead = manifest(url)
    print(f"On air: {m.get('source')}", flush=True)
    if not args.no_countdown:
        print(f"Music starts at {time.strftime('%H:%M:%S', time.localtime(time.time() + qt.COUNTDOWN_S))}", flush=True)
        for left in range(qt.COUNTDOWN_S, 0, -1):
            print(left, flush=True)
            time.sleep(1)

    saved = {c: qt.gain_now(c) for c in (qt.CAPTURE, qt.BOOST)}
    gain = qt.remembered_gain(GAIN_FILE)
    try:
        qt.set_gain(qt.BOOST, 0)
        qt.set_gain(qt.CAPTURE, gain)
        with tempfile.TemporaryDirectory() as folder:
            wav = os.path.join(folder, "music.wav")
            total = int(np.ceil(args.seconds + 2 * qt.LEAD_S))
            print("0: recording", flush=True)
            rec = subprocess.Popen(["arecord", "-q", "-f", "S16_LE", "-r", str(RATE), "-c", "2", "-d", str(total), wav])
            started = time.time()
            time.sleep(qt.LEAD_S)
            qt.sound(serials, (args.seconds + 5) * 1000)
            on = time.time()
            time.sleep(max(0.0, started + total - qt.LEAD_S - time.time()))
            qt.sound(serials, 0)
            off = time.time()
            if rec.wait() != 0:
                sys.exit("arecord failed, so no result")
            x = qt.samples(wav)
    finally:
        qt.sound(serials, 0)
        for control, value in saved.items():
            if value is not None:
                qt.set_gain(control, value)
    print(f"Sound on for {off - on:.1f} s, then stopped.", flush=True)

    # Where the recording falls in the stream: sample n is heard at start + n / rate + D.
    rec_utc = started + server_ahead
    position = rec_utc - m["start_utc_ns"] / 1e9 - m["D_ms"] / 1000
    first = max(m["oldest"], int(position) - 4)
    last = int(position) + total + 4
    src = fetch_source(url, first, last, m["loop_seconds"])
    coarse = (position - first) * RATE

    mics = [bm.solo_gap(ch / 32768, src, RATE, count, coarse) for ch in x]
    heard = [r for r in mics if r]
    if not heard:
        sys.exit("No turns matched the stream: check that the phones are audible and the server is playing.")

    summary = []
    print("\nEach phone against the reference, " + name_of(serials[-1]) + ":")
    for slot, serial in enumerate(serials[:-1]):
        got = [r["phones"][slot] for r in heard if r["phones"].get(slot)]
        if not got:
            print(f"  {name_of(serial)}: no result")
            summary.append((serial, None, {}, None))
            continue
        delay = float(np.mean([g["delay_ms"] for g in got]))
        bands = {f: float(np.mean([g["by_band"][f] for g in got if f in g["by_band"]])) for f in sorted({f for g in got for f in g["by_band"]})}
        scatter = max(g["scatter_ms"] for g in got)
        print(f"  {name_of(serial)}: {abs(delay):.2f} ms {'behind' if delay > 0 else 'ahead'} on music "
              f"(microphones {', '.join(f'{g['delay_ms']:+.2f}' for g in got)}; scatter {scatter:.2f} ms, {sum(g['turns'] for g in got)} turns, "
              f"{sum(g['outliers'] for g in got)} outliers)")
        print("    by band: " + ", ".join(f"{f} Hz {v:+.1f}" for f, v in bands.items()))
        print("    full-range match, for comparison only: " + ", ".join(f"{g['full_range_ms']:+.2f}" for g in got if "full_range_ms" in g))
        summary.append((serial, delay, bands, scatter))

    # By set, when the reel is on air.
    if "reel" in str(m.get("source", "")).lower():
        for slot, serial in enumerate(serials[:-1]):
            sets = {}
            for r in heard:
                ref_rows = [q for q in r["rows"] if q[0] == count - 1 and q[3] >= 0.2]
                if not ref_rows:
                    continue
                ref = np.median([q[2] for q in ref_rows])
                for q in r["rows"]:
                    if q[0] == slot and q[3] >= 0.2:
                        seconds_in = first + (r["coarse"] + q[1]) / RATE
                        which = int(seconds_in // REEL_SET_S) % REEL_SETS
                        sets.setdefault(which, []).append((q[2] - ref) / RATE * 1000)
            print(f"  {name_of(serial)} by set: " + ", ".join(f"set {k + 1} {np.median(v):+.1f}" for k, v in sorted(sets.items())))

    delays = [d for _, d, _, _ in summary if d is not None] + [0.0]
    spread = max(delays) - min(delays)
    verdict = "PASS" if len(delays) == count and spread <= TARGET_MS else "FAIL"
    print(f"\n{verdict}: spread {spread:.2f} ms on music, against a target of {TARGET_MS:.0f} ms. Median match with the stream {np.median([r['match'] for r in heard]):.2f}.")

    warn = []
    for serial in serials:
        s0, s1 = before[serial], status(serial)
        if not qt.locked(s1):
            warn.append(f"{name_of(serial)} lost its lock: {describe(s1)}")
        elif s1["missing"] != s0["missing"] or s1["under"] != s0["under"] or s1["jumps"] != s0["jumps"]:
            warn.append(f"{name_of(serial)} had missing audio, underruns or jumps during the test")
    share = float(np.mean(np.abs(x) >= 32000))
    if share > 0.001:
        warn.append(f"{share:.2%} of the recording clipped, so the delays can be off: the next run uses less gain")
    top = float(np.percentile(np.abs(x), 99.9))
    next_gain = qt.remember_gain(gain, 32767 if share > 0.001 else top, GAIN_FILE)
    if np.median([r["match"] for r in heard]) < 0.3:
        warn.append("the match with the stream is weak: reduce background noise, or bring the phones closer")
    for w in warn:
        print("WARNING:", w)
    print(f"Microphone gain {gain}%, next run {next_gain}%.")

    log = os.path.join(HERE, "music-results.csv")
    new = not os.path.exists(log)
    with open(log, "a", newline="") as f:
        out = csv.writer(f)
        if new:
            out.writerow(["time", "source", "phone", "delay_ms", "scatter_ms", "lag_ms", "seconds", "by_band_ms", "warnings"])
        for slot, (serial, delay, bands, scatter) in enumerate(summary):
            out.writerow([time.strftime("%Y-%m-%d %H:%M:%S"), m.get("source"), name_of(serial), "" if delay is None else f"{delay:.3f}",
                          "" if scatter is None else f"{scatter:.3f}", lags[slot], args.seconds, json.dumps({k: round(v, 2) for k, v in bands.items()}), json.dumps(warn)])


if __name__ == "__main__":
    main()
