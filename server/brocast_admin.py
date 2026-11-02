#!/usr/bin/env python3
"""brocast_admin: a plain page for uploading music sets to the test server.

Each upload is transcoded once, whatever its format or quality, into:

    sets/<id>/set.opus     48 kHz stereo Ogg Opus at 160 kbps, as the design specifies, ready for when phones decode it
    sets/<id>/audio.pcm    raw 48 kHz stereo, which is what today's prototype phones play
    sets/<id>/meta.json    title, artist, set, length and the Opus file's hash

The stream server reads this folder and never writes to it. This page has no password, so it must only ever be
reachable through an SSH tunnel: publish its port on the host's 127.0.0.1, never publicly. The upload is deleted once
transcoded, so keep your own copy.

    python3 brocast_admin.py --library /library --port 8766

Needs Python 3.8+ and ffmpeg with libopus.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

GB = 1024 ** 3
PCM_BYTES_PER_SECOND = 48000 * 4
OPUS_BYTES_PER_SECOND = 20000       # 160 kbps
MIN_SECONDS = 1
ID = re.compile(r"^[0-9a-f]{32}$")


class Library:
    def __init__(self, directory: str, reserve_gb: float, max_gb: float):
        self.directory = directory
        self.sets_dir = os.path.join(directory, "sets")
        self.incoming = os.path.join(directory, "incoming")
        self.reserve = int(reserve_gb * GB)
        self.max = int(max_gb * GB)
        self.lock = threading.Lock()
        self.jobs: queue.Queue = queue.Queue()
        self.progress: dict[str, float] = {}
        os.makedirs(self.sets_dir, exist_ok=True)
        shutil.rmtree(self.incoming, ignore_errors=True)   # anything here was cut off by a restart
        os.makedirs(self.incoming)
        for s in self.sets():
            if s["status"] in ("queued", "transcoding"):
                self.fail(s["id"], "the server restarted during transcoding, so please upload it again")
        threading.Thread(target=self.worker, daemon=True).start()

    def free(self) -> int:
        return shutil.disk_usage(self.directory).free

    def used(self) -> int:
        total = 0
        for root, _, names in os.walk(self.directory):
            for name in names:
                try:
                    total += os.path.getsize(os.path.join(root, name))
                except OSError:
                    pass
        return total

    def room_for(self, size: int) -> str | None:
        """Why this many more bytes can't be stored, or None. The disk is shared, so it always keeps its reserve."""
        if self.free() - size < self.reserve:
            return f"not enough disk: this needs {size / GB:.1f} GB and the shared disk must keep {self.reserve / GB:.0f} GB free"
        if self.used() + size > self.max:
            return f"the library would pass its {self.max / GB:.0f} GB limit, so delete a set first"
        return None

    def path(self, set_id: str, name: str) -> str:
        return os.path.join(self.sets_dir, set_id, name)

    def write_json(self, path: str, value: dict):
        temp = f"{path}.{uuid.uuid4().hex}.tmp"
        with open(temp, "w") as f:
            json.dump(value, f)
        os.replace(temp, path)   # readers see the old file or the new one, never half of one

    def meta(self, set_id: str) -> dict:
        with open(self.path(set_id, "meta.json")) as f:
            return json.load(f)

    def update(self, set_id: str, **fields):
        with self.lock:
            m = self.meta(set_id)
            m.update(fields)
            self.write_json(self.path(set_id, "meta.json"), m)

    def fail(self, set_id: str, why: str):
        self.update(set_id, status="failed", error=why)
        for name in ("audio.pcm", "set.opus"):
            try:
                os.remove(self.path(set_id, name))
            except OSError:
                pass

    def sets(self) -> list[dict]:
        out = []
        for set_id in os.listdir(self.sets_dir):
            if ID.match(set_id):
                try:
                    m = self.meta(set_id)
                except (OSError, ValueError):
                    continue
                m["progress"] = self.progress.get(set_id)
                out.append(m)
        return sorted(out, key=lambda m: m["added"], reverse=True)

    def on_air(self) -> dict | None:
        try:
            with open(os.path.join(self.directory, "on_air.json")) as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def add(self, upload: str, filename: str, title: str, artist: str, set_name: str) -> str:
        set_id = uuid.uuid4().hex
        os.makedirs(os.path.join(self.sets_dir, set_id))
        self.write_json(self.path(set_id, "meta.json"), {
            "id": set_id, "status": "queued", "added": time.time(), "filename": filename,
            "title": title, "artist": artist, "set": set_name,
        })
        self.jobs.put((set_id, upload))
        return set_id

    def worker(self):
        while True:
            set_id, upload = self.jobs.get()
            try:
                self.transcode(set_id, upload)
            except Exception as e:
                self.fail(set_id, str(e)[:300])
            finally:
                self.progress.pop(set_id, None)
                try:
                    os.remove(upload)
                except OSError:
                    pass

    def transcode(self, set_id: str, upload: str):
        tags, seconds = probe(upload)
        if seconds is not None:
            full = int(seconds * (PCM_BYTES_PER_SECOND + OPUS_BYTES_PER_SECOND))
            why = self.room_for(full)
            if why:
                raise ValueError(why)
        m = self.meta(set_id)
        title = m["title"] or tags.get("title") or os.path.splitext(m["filename"])[0]
        artist = m["artist"] or tags.get("artist") or ""
        set_name = m["set"] or tags.get("album") or ""
        self.update(set_id, status="transcoding", title=title, artist=artist, set=set_name)
        pcm, opus = self.path(set_id, "audio.pcm"), self.path(set_id, "set.opus")
        common = ["-map", "0:a:0", "-ac", "2", "-ar", "48000"]
        cmd = ["nice", "-n", "19", "ffmpeg", "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe",
               "-progress", "pipe:1", "-i", upload, *common, "-f", "s16le", "-c:a", "pcm_s16le", pcm,
               *common, "-c:a", "libopus", "-b:a", "160k", "-vbr", "on", "-frame_duration", "20",
               "-page_duration", "1000000", "-map_metadata", "-1", "-metadata", f"title={title}",
               "-metadata", f"artist={artist}", "-metadata", f"album={set_name}", "-f", "ogg", opus]
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        for line in p.stdout:
            if line.startswith("out_time_us=") and seconds:
                value = line.split("=")[1].strip()
                if value.lstrip("-").isdigit():
                    self.progress[set_id] = min(1.0, int(value) / 1e6 / seconds)
            if self.free() < self.reserve:   # for files that didn't say how long they are
                p.kill()
                p.wait()
                raise ValueError(f"stopped: the shared disk must keep {self.reserve / GB:.0f} GB free")
        error = p.stderr.read()
        if p.wait() != 0:
            raise ValueError("ffmpeg couldn't read this file: " + error[:240])
        frames = os.path.getsize(pcm) // 4
        if frames < MIN_SECONDS * 48000:
            raise ValueError("no audio found, or shorter than a second")
        digest = hashlib.sha256()
        with open(opus, "rb") as f:
            while chunk := f.read(1024 * 1024):
                digest.update(chunk)
        self.update(set_id, status="ready", frames=frames, seconds=frames / 48000,
                    pcm_bytes=os.path.getsize(pcm), opus_bytes=os.path.getsize(opus), sha256=digest.hexdigest())

    def put_on_air(self, set_id: str):
        m = self.meta(set_id)
        if m["status"] != "ready":
            raise ValueError("this set isn't ready")
        label = " · ".join(x for x in (m["title"], m["artist"]) if x)
        start = (time.time_ns() // 1_000_000_000 + 1) * 1_000_000_000
        self.write_json(os.path.join(self.directory, "on_air.json"), {"id": set_id, "label": label, "start_ns": start})

    def take_off_air(self):
        try:
            os.remove(os.path.join(self.directory, "on_air.json"))
        except OSError:
            pass

    def delete(self, set_id: str):
        air = self.on_air()
        if air and air.get("id") == set_id:
            raise ValueError("this set is on air, so take it off first")
        if self.meta(set_id)["status"] in ("queued", "transcoding"):
            raise ValueError("this set is still transcoding")
        shutil.rmtree(os.path.join(self.sets_dir, set_id))


def probe(path: str) -> tuple[dict, float | None]:
    """The file's tags and length. Raises if it holds no audio."""
    cmd = ["ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a:0", "-show_entries",
           "stream=codec_name:format=duration:format_tags:stream_tags", "-of", "json", path]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise ValueError("this doesn't look like an audio file")
    info = json.loads(r.stdout)
    if not info.get("streams"):
        raise ValueError("this file has no audio")
    tags = {**(info["streams"][0].get("tags") or {}), **(info.get("format", {}).get("tags") or {})}
    try:
        seconds = float(info["format"]["duration"])
    except (KeyError, ValueError):
        seconds = None
    return {k.lower(): v for k, v in tags.items()}, seconds


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Brocast test sets</title>
<style>
:root { color-scheme: dark; font: 16px/1.45 system-ui, sans-serif; }
body { max-width: 760px; margin: 0 auto; padding: 16px; background: #111; color: #eee; }
h1 { font-size: 22px; margin: 8px 0 2px; } h2 { font-size: 16px; margin: 24px 0 8px; }
p { margin: 4px 0; } .muted { color: #aaa; font-size: 14px; } .warn { color: #fb5; } .bad { color: #f88; }
form, .set { border: 1px solid #444; border-radius: 10px; padding: 12px; margin: 8px 0; }
label { display: block; margin: 8px 0; font-size: 14px; } input[type=text], input[type=file] { display: block; width: 100%; box-sizing: border-box; padding: 8px; margin-top: 2px; font: inherit; }
button { min-height: 44px; padding: 0 16px; margin: 8px 8px 0 0; font: inherit; cursor: pointer; }
progress { width: 100%; height: 12px; margin-top: 8px; } .air { border-color: #cf5; } .tag { color: #cf5; font-weight: 700; }
.title { font-weight: 600; overflow-wrap: anywhere; }
</style></head><body>
<h1>Brocast test sets</h1>
<p class="muted" id="disk"></p>
<p class="muted">Only upload music cleared for this. Phones can hear what's on air, and the stream has no password.</p>
<form id="form">
  <label>Music file (any audio format; keep your own copy, because the upload is deleted once transcoded)
    <input type="file" id="file" accept="audio/*,.flac,.wav,.mp3,.m4a,.aac,.ogg,.opus,.aiff" required></label>
  <label>Title (optional: uses the file's tags or name)<input type="text" id="title" maxlength="200"></label>
  <label>Artist (optional)<input type="text" id="artist" maxlength="200"></label>
  <label>Set or show (optional)<input type="text" id="set" maxlength="200"></label>
  <button id="go">Upload</button>
  <progress id="bar" max="1" value="0" hidden></progress>
  <p id="message" role="status"></p>
</form>
<h2>On air</h2>
<div id="air"></div>
<h2>Sets</h2>
<div id="sets"></div>
<script>
const $ = id => document.getElementById(id);
const headers = {"X-Brocast": "1"};
const gb = n => (n / 1073741824).toFixed(1) + " GB";
const mb = n => Math.round(n / 1048576) + " MB";
const time = s => { s = Math.round(s); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60);
  return (h ? h + " h " : "") + m + " min " + (s % 60) + " s"; };
function el(tag, text, cls) { const e = document.createElement(tag); if (text) e.textContent = text; if (cls) e.className = cls; return e; }
async function act(url, method) {
  const r = await fetch(url, {method, headers}); const j = await r.json().catch(() => ({}));
  if (!r.ok) alert(j.error || "That didn't work"); refresh();
}
async function refresh() {
  const r = await fetch("/api/sets"); const d = await r.json();
  $("disk").textContent = "Library " + gb(d.used) + " of " + gb(d.max) + " · shared disk has " + gb(d.free) + " free and keeps " + gb(d.reserve);
  const air = $("air"); air.replaceChildren();
  const onAir = d.on_air;
  air.append(el("p", onAir ? onAir.label : "The test clicks: nothing else is on air"));
  if (onAir) { const b = el("button", "Back to test clicks"); b.onclick = () => act("/api/air", "DELETE"); air.append(b); }
  air.append(el("p", "Phones follow a change by themselves, after up to a minute of silence.", "muted"));
  const list = $("sets"); list.replaceChildren();
  if (!d.sets.length) list.append(el("p", "No sets yet", "muted"));
  for (const s of d.sets) {
    const on = onAir && onAir.id === s.id;
    const box = el("div", null, "set" + (on ? " air" : ""));
    box.append(el("p", s.title || s.filename, "title"));
    const by = [s.artist, s.set].filter(Boolean).join(" · "); if (by) box.append(el("p", by, "muted"));
    if (s.status === "ready") {
      box.append(el("p", time(s.seconds) + " · disk " + mb(s.pcm_bytes + s.opus_bytes) + " · Opus " + mb(s.opus_bytes), "muted"));
      if (on) box.append(el("p", "On air", "tag"));
      else { const a = el("button", "Put on air"); a.onclick = () => act("/api/sets/" + s.id + "/air", "POST"); box.append(a); }
      if (!on) { const x = el("button", "Delete"); x.onclick = () => { if (confirm("Delete this set?")) act("/api/sets/" + s.id, "DELETE"); }; box.append(x); }
    } else if (s.status === "failed") {
      box.append(el("p", "Failed: " + s.error, "bad"));
      const x = el("button", "Remove"); x.onclick = () => act("/api/sets/" + s.id, "DELETE"); box.append(x);
    } else {
      box.append(el("p", s.status === "queued" ? "Waiting to transcode" : "Transcoding" + (s.progress != null ? " " + Math.round(s.progress * 100) + "%" : ""), "warn"));
    }
    list.append(box);
  }
}
$("form").onsubmit = e => {
  e.preventDefault();
  const file = $("file").files[0]; if (!file) return;
  const q = new URLSearchParams({name: file.name, title: $("title").value, artist: $("artist").value, set: $("set").value});
  const x = new XMLHttpRequest(); x.open("PUT", "/api/upload?" + q); x.setRequestHeader("X-Brocast", "1");
  $("bar").hidden = false; $("go").disabled = true; $("message").textContent = "Uploading…";
  x.upload.onprogress = p => { $("bar").value = p.loaded / p.total; };
  x.onloadend = () => {
    $("go").disabled = false; $("bar").hidden = true; let j = {}; try { j = JSON.parse(x.responseText); } catch (_) {}
    if (x.status === 200) { $("message").textContent = "Uploaded. Transcoding now."; $("form").reset(); }
    else { $("message").textContent = j.error || "The upload didn't finish. Please try again."; }
    refresh();
  };
  x.send(file);
};
refresh(); setInterval(refresh, 2000);
</script></body></html>
"""


def make_handler(lib: Library):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def send(self, code: int, body: bytes, ctype: str):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def reply(self, code: int = 200, **fields):
            self.send(code, json.dumps(fields).encode(), "application/json")

        def allowed(self, changing: bool) -> bool:
            """Refuses other websites in the browser: they can't send this header or use this host name."""
            host = (self.headers.get("Host") or "").rsplit(":", 1)[0]
            if host not in ("localhost", "127.0.0.1") or (changing and self.headers.get("X-Brocast") != "1"):
                self.close_connection = True
                self.reply(403, error="not allowed")
                return False
            return True

        def do_GET(self):
            if not self.allowed(False):
                return
            path = urlparse(self.path).path
            if path == "/":
                self.send(200, PAGE.encode(), "text/html; charset=utf-8")
            elif path == "/api/sets":
                self.reply(sets=lib.sets(), on_air=lib.on_air(), free=lib.free(), used=lib.used(), max=lib.max, reserve=lib.reserve)
            else:
                self.reply(404, error="not found")

        def do_PUT(self):
            if not self.allowed(True):
                return
            url = urlparse(self.path)
            if url.path != "/api/upload":
                self.close_connection = True
                self.reply(404, error="not found")
                return
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                self.close_connection = True
                self.reply(411, error="the upload needs a length")
                return
            why = lib.room_for(length)
            if why:
                self.close_connection = True   # the browser may still be sending, so don't read it
                self.reply(507, error=why)
                return
            q = parse_qs(url.query)
            field = lambda k: (q.get(k) or [""])[0].strip()[:200]
            temp = os.path.join(lib.incoming, uuid.uuid4().hex)
            try:
                left = length
                with open(temp, "wb") as f:
                    while left:
                        chunk = self.rfile.read(min(left, 1024 * 1024))
                        if not chunk:
                            raise ConnectionError("the upload stopped early")
                        f.write(chunk)
                        left -= len(chunk)
            except (OSError, ConnectionError):
                try:
                    os.remove(temp)
                except OSError:
                    pass
                self.close_connection = True
                return
            set_id = lib.add(temp, field("name") or "upload", field("title"), field("artist"), field("set"))
            self.reply(id=set_id)

        def do_POST(self):
            if not self.allowed(True):
                return
            m = re.fullmatch(r"/api/sets/([0-9a-f]{32})/air", urlparse(self.path).path)
            if not m:
                self.reply(404, error="not found")
                return
            try:
                lib.put_on_air(m.group(1))
                self.reply()
            except (OSError, ValueError) as e:
                self.reply(400, error=str(e))

        def do_DELETE(self):
            if not self.allowed(True):
                return
            path = urlparse(self.path).path
            if path == "/api/air":
                lib.take_off_air()
                self.reply()
                return
            m = re.fullmatch(r"/api/sets/([0-9a-f]{32})", path)
            if not m:
                self.reply(404, error="not found")
                return
            try:
                lib.delete(m.group(1))
                self.reply()
            except (OSError, ValueError) as e:
                self.reply(400, error=str(e))

    return Handler


def main():
    p = argparse.ArgumentParser(description="Upload page for the Brocast test server. Keep it behind an SSH tunnel.")
    p.add_argument("--library", required=True)
    p.add_argument("--port", type=int, default=8766)
    p.add_argument("--bind", default="127.0.0.1")
    p.add_argument("--reserve-gb", type=float, default=3, help="free space the shared disk must always keep")
    p.add_argument("--max-gb", type=float, default=8, help="most the library may use")
    a = p.parse_args()
    lib = Library(a.library, a.reserve_gb, a.max_gb)
    print(f"upload page on {a.bind}:{a.port}, library in {a.library}", flush=True)
    ThreadingHTTPServer((a.bind, a.port), make_handler(lib)).serve_forever()


if __name__ == "__main__":
    main()
