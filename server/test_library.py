#!/usr/bin/env python3
"""Tests the upload page and the stream server together, on this machine. Needs ffmpeg.

    python3 server/test_library.py
"""
import http.client
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ADMIN, STREAM = 18766, 18765
results = []


def check(ok, what):
    results.append(ok)
    print(("  ok   " if ok else "  FAIL ") + what)


def call(port, method, path, body=None, headers=None):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
    c.request(method, path, body, {"X-Brocast": "1", **(headers or {})} if headers is not False else {})
    r = c.getresponse()
    data = r.read()
    c.close()
    return r.status, data


def api(method, path, body=None):
    status, data = call(ADMIN, method, path, body)
    return status, json.loads(data)


def sets():
    return api("GET", "/api/sets")[1]


def wait_for(set_id):
    for _ in range(300):
        s = next(x for x in sets()["sets"] if x["id"] == set_id)
        if s["status"] in ("ready", "failed"):
            return s
        time.sleep(0.1)
    raise TimeoutError(set_id)


def make(path, *args):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3:sample_rate=44100",
                    *args, path], check=True)


def main():
    with tempfile.TemporaryDirectory() as t:
        lib = os.path.join(t, "library")
        os.mkdir(lib)
        admin = subprocess.Popen([sys.executable, os.path.join(HERE, "brocast_admin.py"), "--library", lib,
                                  "--port", str(ADMIN), "--reserve-gb", "0.001", "--max-gb", "1"])
        stream = subprocess.Popen([sys.executable, os.path.join(HERE, "beachsync_server.py"), "--clicks-split",
                                   "--library", lib, "--port", str(STREAM), "--bind", "127.0.0.1"], stdout=subprocess.DEVNULL)
        try:
            time.sleep(1)
            print("Access")
            check(call(ADMIN, "DELETE", "/api/air", headers=False)[0] == 403, "a change without the page's header is refused")
            check(call(ADMIN, "GET", "/", headers={"Host": "evil.example"})[0] == 403, "another host name is refused")
            check(call(ADMIN, "GET", "/")[0] == 200, "the page itself loads")

            print("Uploads in different formats")
            mp3, flac = os.path.join(t, "a.mp3"), os.path.join(t, "b.flac")
            make(mp3, "-metadata", "title=From Tags", "-metadata", "artist=Tag Artist", "-b:a", "128k")
            make(flac, "-ac", "1")
            with open(mp3, "rb") as f:
                status, r = api("PUT", "/api/upload?name=a.mp3", f.read())
            check(status == 200, "an MP3 uploads")
            a = wait_for(r["id"])
            check(a["status"] == "ready", f"and transcodes ({a.get('error', 'ok')})")
            check(a["title"] == "From Tags" and a["artist"] == "Tag Artist", "with its title and artist from the tags")
            with open(flac, "rb") as f:
                status, r = api("PUT", "/api/upload?name=b.flac&title=Typed&set=Golden", f.read())
            b = wait_for(r["id"])
            check(b["status"] == "ready" and b["title"] == "Typed" and b["set"] == "Golden", "a mono 44.1 kHz FLAC does too, using typed names")
            check(abs(b["seconds"] - 3) < 0.05, f"and keeps its length ({b['seconds']:.3f} s)")
            check(not os.listdir(os.path.join(lib, "incoming")), "the uploads are gone once transcoded")

            opus = os.path.join(lib, "sets", b["id"], "set.opus")
            info = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", opus],
                                             capture_output=True, text=True).stdout)
            st = info["streams"][0]
            check(st["codec_name"] == "opus" and st["sample_rate"] == "48000" and st["channels"] == 2, "the file kept is 48 kHz stereo Opus")
            tags = {k.lower(): v for k, v in info["format"].get("tags", {}).items()} or {k.lower(): v for k, v in st.get("tags", {}).items()}
            check(tags.get("title") == "Typed" and tags.get("album") == "Golden", "with the names in its tags")
            status, r = api("PUT", "/api/upload?name=junk.mp3", b"this is not audio" * 100)
            j = wait_for(r["id"])
            check(j["status"] == "failed" and "audio" in j["error"], "a file that isn't audio fails with a reason")
            check(api("DELETE", "/api/sets/" + j["id"])[0] == 200, "and can be removed")

            print("Limits")
            c = http.client.HTTPConnection("127.0.0.1", ADMIN, timeout=10)
            c.putrequest("PUT", "/api/upload?name=huge")
            c.putheader("X-Brocast", "1")
            c.putheader("Content-Length", str(2 * 1024 ** 3))
            c.endheaders()
            r = c.getresponse()
            check(r.status == 507, "an upload past the library limit is refused before any of it is stored")
            c.close()

            print("On air")
            before = json.loads(call(STREAM, "GET", "/manifest.json")[1])
            check(before["source"] == "clicks-split", "the test clicks play while nothing is on air")
            status, _ = api("POST", f"/api/sets/{b['id']}/air")
            check(status == 200, "a set goes on air")
            time.sleep(2.2)
            m = json.loads(call(STREAM, "GET", "/manifest.json")[1])
            check(m["source"] == "Typed" and m["start_utc_ns"] > before["start_utc_ns"], "the stream server follows, with a new start time")
            check(m["newest"] >= 0, f"and is live (newest segment {m['newest']})")
            status, seg = call(STREAM, "GET", f"/seg/{m['newest']}.opus")
            check(status == 200 and 100 < len(seg) < 40000, f"its blocks are compressed ({len(seg)} bytes for 1 s)")
            check(call(STREAM, "GET", f"/seg/{m['newest']}.pcm")[0] == 404, "and raw audio isn't available at all")
            with open(os.path.join(lib, "sets", b["id"], "set.opus"), "rb") as f:
                whole = f.read()
            check(call(STREAM, "GET", "/on_air.opus") == (200, whole), "the whole compressed file downloads")
            status, listed = call(STREAM, "GET", "/library.json")
            items = json.loads(listed)["editions"][-1]["items"] if status == 200 else []
            check(any(i["id"] == b["id"] and i["sha256"] and i["seconds"] >= 3 for i in items), "the library lists it, with its hash")
            check(call(STREAM, "GET", f"/file/{b['id']}.opus") == (200, whole), "and phones can download it once")
            check(call(STREAM, "GET", "/file/../meta.opus")[0] in (400, 404), "a bad file id is refused")
            check(api("DELETE", "/api/sets/" + b["id"])[0] == 400, "a set on air can't be deleted")
            check(api("DELETE", "/api/air")[0] == 200, "it can be taken off air")
            time.sleep(0.1)
            check(json.loads(call(STREAM, "GET", "/manifest.json")[1])["source"] == "clicks-split", "and the clicks come back")
            check(api("DELETE", "/api/sets/" + b["id"])[0] == 200, "then it can be deleted")
            check(not os.path.exists(os.path.join(lib, "sets", b["id"])), "which removes its files")
        finally:
            admin.terminate()
            stream.terminate()
    print("ALL PASSED" if all(results) else "FAILED")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
