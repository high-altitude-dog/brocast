#!/usr/bin/env python3
"""The acoustic two-phone test as one command. The phones make no sound except during the test itself.

    python3 measure/quicktest.py --prepare       # silent: start the phones, wait for GPS, then exit
    python3 measure/quicktest.py                 # 10 s countdown, recording starts, about 10 s of ticks, then silence
    python3 measure/quicktest.py --lag-a 19      # the same with phone A's headphone lag at 19 ms
    python3 measure/quicktest.py --finish        # end the runs when you're done, so GPS stops

Phone A is the Galaxy and phone B the Pixel, or pass --a and --b with adb serials. Both play through their speakers. Put
them either side of the centre of the laptop's screen, level with each other and facing it: it records both microphones
in the screen's bezel and averages them, which cancels the difference in distance. A positive gap means phone B is later,
as in the rest of the repo.

Both phones play the same pitch, with phone B 60 ms after phone A, and the pitch steps through 500, 1000, 2000, 4000 and
8000 Hz, one every 200 ms. The clicks never overlap, the order says which phone is which, and the result shows the gap at
each pitch. It only tells the phones apart at gaps under 30 ms, so it needs a GPS lock on both, which it waits for.

Phones already running the right settings aren't restarted, so they keep their GPS lock. Sound stops as soon as recording
ends, and each phone also stops by itself after 30 s in case this script dies.

Needs adb, arecord, amixer and numpy. Recordings are deleted once analysed, and each result is added to results.csv.
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
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import beachsync_measure as bm  # noqa: E402

FILES = "/sdcard/Android/data/org.beachsync.proto/files"
LOCK_TIMEOUT_S = 180
LOCK_HOLD_S = 4
CAPTURE, BOOST = "Capture", "Internal Mic Boost"
GAIN_STEPS = (100, 60, 40, 25, 15, 8)
DEFAULT_GAIN = 60
GAIN_FILE = os.path.join(HERE, "gain.txt")
LEAD_S = 0.5    # recording runs this long before the ticks start and after they stop
COUNTDOWN_S = 10
FAILSAFE_MS = 30000


def adb(serial, *args, stdin=None):
    return subprocess.run(["adb", "-s", serial, *args], input=stdin, capture_output=True, text=True).stdout


def find_phones(a, b):
    if a and b:
        return a, b
    found = {}
    for line in subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device":
            found[parts[0]] = adb(parts[0], "shell", "getprop", "ro.product.model").strip()
        elif len(parts) == 2:
            sys.exit(f"phone {parts[0]} is {parts[1]}: unlock it and tap Allow")
    a = a or next((s for s, m in found.items() if "Pixel" not in m), None)
    b = b or next((s for s, m in found.items() if "Pixel" in m), None)
    if not a or not b:
        sys.exit(f"need both phones on USB, and found: {found or 'none'}")
    return a, b


def latest(serial, kind):
    return adb(serial, "shell", f"cd {FILES}; f=$(ls -t | grep -- '-{kind}' | head -1); echo $f").strip()


def is_running(serial, role, lag):
    """True if this phone is already in this role at this lag, with a run still writing its log."""
    info = adb(serial, "shell", f"cat {FILES}/{latest(serial, 'info')}")
    if f"tick pattern {role}" not in info or f"headphone lag: {float(lag)} ms" not in info:
        return False
    age = adb(serial, "shell", f"cd {FILES}; echo $(( $(date +%s) - $(stat -c %Y {latest(serial, 'status')}) ))").strip()
    return age.isdigit() and int(age) < 5 and "isForeground=true" in adb(serial, "shell", "dumpsys", "activity", "services", "org.beachsync.proto")


def setting(xml, kind, name, value):
    """Changes one saved setting and leaves the others, such as the server address, as they are."""
    if kind == "boolean":
        new = f'<boolean name="{name}" value="{value}" />'
        old = rf'<boolean name="{name}" value="[^"]*" />'
    else:
        new = f'<string name="{name}">{value}</string>'
        old = rf'<string name="{name}">[^<]*</string>'
    return re.sub(old, new, xml) if re.search(old, xml) else xml.replace("</map>", new + "</map>")


def restart(serial, role, lag):
    adb(serial, "shell", "am", "force-stop", "org.beachsync.proto")
    path = "shared_prefs/proto.xml"
    xml = adb(serial, "shell", f"run-as org.beachsync.proto cat {path} 2>/dev/null")
    if "<map" not in xml:
        xml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>'
    xml = setting(xml, "boolean", "stream", "false")
    xml = setting(xml, "string", "lag", lag)
    xml = setting(xml, "boolean", "satellites", "true")
    adb(serial, "shell", f"run-as org.beachsync.proto sh -c 'mkdir -p shared_prefs; cat > {path}'", stdin=xml)
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb(serial, "shell", "wm", "dismiss-keyguard")
    adb(serial, "shell", "am", "start", "-W", "-n", "org.beachsync.proto/.MainActivity", "--es", "tickPattern", role)
    for _ in range(10):
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
        m = re.search(r'text="START RUN"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', adb(serial, "shell", "cat", "/sdcard/ui.xml"), re.I)
        if m:
            x0, y0, x1, y1 = map(int, m.groups())
            adb(serial, "shell", "input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))
            return
        time.sleep(0.5)
    sys.exit(f"{serial}: couldn't find the Start button")


def status(serial):
    r = adb(serial, "shell", f"cd {FILES}; tail -n 1 $(ls -t | grep -- '-status' | head -1)").strip().split(",")
    if len(r) < 25:
        return None
    return dict(src=r[2], err=float(r[3] or 1e12) / 1e6, sats=int(r[7] or 0), age=float(r[11] or 1e9), under=int(r[19] or 0),
                held=float(r[24] or 0) / 1e6)


def locked(d):
    """Includes the gap being closed, which takes about a minute after a phone switches to GPS and shifts the result until then."""
    return bool(d) and d["src"] == "GPS" and d["err"] < 1.0 and d["sats"] >= 6 and d["age"] < 2000 and abs(d["held"]) < 0.02


def describe(d):
    return f"{d['src'] or 'no source'} ±{d['err']:.3f} ms, {d['sats']} satellites, last report {d['age'] / 1000:.1f} s ago" if d else "not logging yet"


def wait_for_lock(a, b):
    start = time.time()
    since = None
    while True:
        sa, sb = status(a), status(b)
        if locked(sa) and locked(sb):
            since = since or time.time()
            if time.time() - since >= LOCK_HOLD_S:
                return time.time() - start, sa, sb
        else:
            since = None
        if time.time() - start > LOCK_TIMEOUT_S:
            sys.exit(f"no GPS lock after {LOCK_TIMEOUT_S} s, so no test ran. Move them closer to a window.\n"
                     f"  phone A: {describe(sa)}\n  phone B: {describe(sb)}")
        time.sleep(1)


def prepare(a, b, lag_a, lag_b):
    """Silent. Gets both phones running and locked."""
    restarted = []
    for serial, role, lag in ((a, "A", lag_a), (b, "B", lag_b)):
        if not is_running(serial, role, lag):
            restart(serial, role, lag)
            restarted.append(role)
    lock_s, sa, sb = wait_for_lock(a, b)
    print(f"Ready and silent: {'restarted phone ' + ' and '.join(restarted) if restarted else 'phones were already running'}, GPS locked in {lock_s:.0f} s.", flush=True)
    print(f"  phone A: {describe(sa)}\n  phone B: {describe(sb)}", flush=True)
    return sa, sb


def sound(phones, ms):
    """Both phones at once, so neither starts or stops noticeably before the other."""
    procs = [subprocess.Popen(["adb", "-s", s, "shell", "am", "broadcast", "-n", "org.beachsync.proto/.TickWindowReceiver", "--el", "ms", str(ms)],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) for s in phones]
    for p in procs:
        p.wait()


def record(path, seconds):
    subprocess.run(["arecord", "-q", "-f", "S16_LE", "-r", "48000", "-c", "2", "-d", str(seconds), path], check=True)


def samples(path):
    """One row per microphone."""
    with wave.open(path) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)
        return x.reshape(-1, w.getnchannels()).T


def gain_now(control):
    m = re.search(r"\[(\d+)%\]", subprocess.run(["amixer", "-c", "0", "sget", control], capture_output=True, text=True).stdout)
    return int(m.group(1)) if m else None


def set_gain(control, percent):
    subprocess.run(["amixer", "-c", "0", "-q", "sset", control, f"{percent}%"], capture_output=True)


def remembered_gain(path=GAIN_FILE):
    try:
        with open(path) as f:
            g = int(f.read())
        return g if g in GAIN_STEPS else DEFAULT_GAIN
    except (OSError, ValueError):
        return DEFAULT_GAIN


def remember_gain(gain, peak, path=GAIN_FILE):
    """Recording starts before the ticks, so the level can only be corrected for the next run. Each test keeps its own
    file, because music and ticks reach the microphones at very different levels."""
    i = GAIN_STEPS.index(gain)
    if peak >= 30000 and i + 1 < len(GAIN_STEPS):
        i += 1
    elif peak < 4000 and i > 0:
        i -= 1
    with open(path, "w") as f:
        f.write(str(GAIN_STEPS[i]))
    return GAIN_STEPS[i]


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--prepare", action="store_true", help="only start the phones and wait for GPS, silently")
    p.add_argument("--finish", action="store_true", help="end the runs on both phones")
    p.add_argument("--seconds", type=int, default=10, help="how long the ticks last; each second gives one reading at each pitch")
    p.add_argument("--no-countdown", action="store_true")
    p.add_argument("--lag-a", type=float, default=0.0, help="phone A's headphone lag, ms; a larger lag plays earlier")
    p.add_argument("--lag-b", type=float, default=0.0, help="phone B's headphone lag, ms")
    p.add_argument("--a", help="phone A's adb serial")
    p.add_argument("--b", help="phone B's adb serial")
    args = p.parse_args()

    a, b = find_phones(args.a, args.b)
    if args.finish:
        for s in (a, b):
            adb(s, "shell", "am", "force-stop", "org.beachsync.proto")
        print("Runs ended on both phones.")
        return

    sa0, sb0 = prepare(a, b, args.lag_a, args.lag_b)
    if args.prepare:
        return

    if not args.no_countdown:
        print(f"Ticks start at {time.strftime('%H:%M:%S', time.localtime(time.time() + COUNTDOWN_S))}", flush=True)
        for left in range(COUNTDOWN_S, 0, -1):
            print(left, flush=True)
            time.sleep(1)

    saved = {c: gain_now(c) for c in (CAPTURE, BOOST)}
    start = time.time()
    gain = remembered_gain()
    try:
        set_gain(BOOST, 0)    # at full boost, the bezel microphones sit near full scale even in a quiet room
        set_gain(CAPTURE, gain)
        with tempfile.TemporaryDirectory() as folder:
            wav = os.path.join(folder, "ticks.wav")
            total = int(np.ceil(args.seconds + 2 * LEAD_S))
            print("0: recording", flush=True)
            rec = subprocess.Popen(["arecord", "-q", "-f", "S16_LE", "-r", "48000", "-c", "2", "-d", str(total), wav])
            start = time.time()
            time.sleep(LEAD_S)
            sound((a, b), FAILSAFE_MS)
            ticks_on = time.time()
            time.sleep(max(0.0, start + total - LEAD_S - time.time()))
            sound((a, b), 0)
            stopped = time.time()
            if rec.wait() != 0:
                sys.exit("arecord failed, so no result")
            x = samples(wav)
            mics = [bm.pattern_gap(ch / 32768, 48000) for ch in x]
            # Peaks of the clicks themselves, so a brief spike elsewhere in the recording doesn't count as clipping.
            peaks = [np.abs(ch[int(t * 48000):int(t * 48000) + 240]).max() for ch, r in zip(x, mics) if r for t in r["times"]]
            level = int(np.median(peaks)) if peaks else int(np.abs(x).max())
            clipped_share = float(np.mean(np.array(peaks) >= 32000)) if peaks else 0.0
            before = np.abs(x[:, :int(0.3 * 48000)]).max()      # the silence before the ticks
            noisy = bool(peaks) and before > 0.25 * float(np.percentile(peaks, 10))
    finally:
        sound((a, b), 0)
        for control, value in saved.items():
            if value is not None:
                set_gain(control, value)

    next_gain = remember_gain(gain, level)
    print(f"Sound on for {stopped - ticks_on:.1f} s, then stopped. Microphone gain {gain}%, typical click peak {level}, next run {next_gain}%.")
    heard = [r for r in mics if r]
    if not heard:
        sys.exit("Too few clicks found: check that both phones are audible, then run it again.")
    # With the phones either side of the screen's centre, averaging the two microphones cancels the distance difference.
    result = {
        "gap_ms": float(np.mean([r["gap_ms"] for r in heard])),
        "scatter_ms": max(r["scatter_ms"] for r in heard), "clicks": sum(r["clicks"] for r in heard), "outliers": sum(r["outliers"] for r in heard),
    }
    per_pitch = {f: float(np.mean([r["per_pitch"][f]["gap_ms"] for r in heard if f in r["per_pitch"]]))
                 for f in sorted({f for r in heard for f in r["per_pitch"]})}
    print("Each microphone: " + ", ".join("none" if r is None else f"{r['gap_ms']:+.2f} ms" for r in mics))
    print("Gap at each pitch:")
    for f, v in per_pitch.items():
        print(f"  {f:5d} Hz  {v:+7.2f} ms")
    sa1, sb1 = status(a), status(b)
    warn = []
    if len(heard) < len(mics):
        warn.append("only one microphone heard both phones")
    elif len(heard) == 2 and abs(heard[0]["gap_ms"] - heard[1]["gap_ms"]) > 1.0:
        warn.append("the two microphones disagree by more than 1 ms, more than the phones' positions can explain: treat this as unreliable")
    for name, s0, s1 in (("A", sa0, sa1), ("B", sb0, sb1)):
        if not locked(s1):
            warn.append(f"phone {name} lost its lock: {describe(s1)}")
        elif s1["under"] != s0["under"]:
            warn.append(f"phone {name} had underruns")
    if clipped_share > 0.1:
        warn.append(f"{clipped_share:.0%} of the clicks clipped, so the gap can be off: the next run uses less gain")
    if noisy:
        warn.append("there was noise just before the ticks, so run it again if the room wasn't quiet")
    if result["outliers"] > 0.1 * result["clicks"]:
        warn.append("more than 10% of the readings were outliers, which is usually background noise: run it again")
    level_diff = max(r["level_diff_db"] for r in heard)
    if level_diff > 15:
        warn.append(f"the phones differ by about {level_diff:.0f} dB, so an echo could pass for the quieter one: even out their volumes")
    gap = result["gap_ms"]
    later = "phone B" if gap > 0 else "phone A"
    print(f"GAP {gap:+.2f} ms ({later} later), scatter {result['scatter_ms']:.2f} ms, {result['clicks']} pairs, {result['outliers']} outliers.")
    for w in warn:
        print("WARNING:", w)

    log = os.path.join(HERE, "results.csv")
    new = not os.path.exists(log)
    with open(log, "a", newline="") as f:
        out = csv.writer(f)
        if new:
            out.writerow(["time", "gap_ms", "bias_ms", "scatter_ms", "clicks", "outliers", "lag_a_ms", "lag_b_ms", "seconds", "warnings", "per_pitch_ms"])
        out.writerow([time.strftime("%Y-%m-%d %H:%M:%S"), f"{gap:.3f}", "", f"{result['scatter_ms']:.3f}",
                      result["clicks"], result["outliers"], args.lag_a, args.lag_b, args.seconds, json.dumps(warn),
                      json.dumps({f: round(v, 3) for f, v in per_pitch.items()})])


if __name__ == "__main__":
    main()
