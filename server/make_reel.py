#!/usr/bin/env python3
"""Builds the music test's reel: 10 s from the middle of every set in the library, one after another, as a new set.

    python3 server/make_reel.py --ssh "ssh -i <key> <user>@<server-ip>"

It reads the sets from the brocast-admin container over ssh, and uploads the reel to the upload page, which must be
reachable at --admin through the SSH tunnel in README.md. It only reads the sets, and adds one more.
Pass --air to put the reel on air once it's ready. Needs nothing but the standard library.
"""
import argparse
import json
import shlex
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import wave
import io

TITLE = "Test reel"
SECONDS = 10

READER = """
import json, os, sys
root = "/library/sets"
out = sys.stdout.buffer
names = []
for d in sorted(os.listdir(root)):
    try:
        meta = json.load(open(os.path.join(root, d, "meta.json")))
    except OSError:
        continue
    if meta.get("status") != "ready" or meta.get("title") == %r:
        continue
    pcm = os.path.join(root, d, "audio.pcm")
    size = os.path.getsize(pcm)
    want = %d * 192000
    if size < want:
        continue
    names.append(meta.get("title") or d)
    with open(pcm, "rb") as f:
        f.seek(((size - want) // 2) // 4 * 4)
        out.write(f.read(want))
print(json.dumps(names), file=sys.stderr)
""" % (TITLE, SECONDS)


def call(admin, path, method="GET", data=None, headers=None):
    req = urllib.request.Request(admin + path, data=data, method=method, headers={"X-Brocast": "1", **(headers or {})})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--ssh", required=True, help="the ssh command that reaches the server")
    p.add_argument("--admin", default="http://localhost:8766")
    p.add_argument("--air", action="store_true")
    a = p.parse_args()

    run = subprocess.run(shlex.split(a.ssh) + ["docker exec -i brocast-admin python3 -"], input=READER.encode(), capture_output=True, timeout=300)
    if run.returncode:
        sys.exit("couldn't read the sets: " + run.stderr.decode()[-400:])
    names = json.loads(run.stderr.decode().strip().splitlines()[-1])
    pcm = run.stdout
    print(f"{len(names)} sets, {len(pcm) / 192000:.0f} s:")
    for i, n in enumerate(names):
        print(f"  {i + 1}. {n}")

    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(48000)
        w.writeframes(pcm)
    q = urllib.parse.urlencode({"name": "test-reel.wav", "title": TITLE, "artist": "Beach Sync", "set": "Music test"})
    set_id = call(a.admin, "/api/upload?" + q, "PUT", buf.getvalue())["id"]
    print("uploaded as", set_id)

    for _ in range(120):
        sets = {s["id"]: s for s in call(a.admin, "/api/sets")["sets"]}
        status = sets[set_id].get("status")
        if status == "ready":
            break
        if status not in ("queued", "transcoding"):
            sys.exit(f"transcoding ended as {status}")
        time.sleep(2)
    else:
        sys.exit("transcoding is taking too long")
    print("transcoded")
    if a.air:
        call(a.admin, f"/api/sets/{set_id}/air", "POST", b"")
        print("on air")


if __name__ == "__main__":
    main()
