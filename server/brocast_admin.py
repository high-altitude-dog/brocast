#!/usr/bin/env python3
"""brocast_admin: a plain page for uploading music sets to the test server.

Each upload is transcoded once, whatever its format or quality, into:

    sets/<id>/set.opus     48 kHz stereo Ogg Opus at 160 kbps, as the design specifies, ready for when phones decode it
    sets/<id>/audio.pcm    raw 48 kHz stereo, kept on the server only: only set.opus ever leaves it
    sets/<id>/meta.json    title, artist, set, length and the Opus file's hash

The stream server reads this folder and never writes to it. This page has no password, so it must only ever be
reachable through an SSH tunnel: publish its port on the host's 127.0.0.1, never publicly. The upload is deleted once
transcoded, so keep your own copy.

    python3 brocast_admin.py --library /library --port 8766

Needs Python 3.8+ and ffmpeg with libopus.
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import math
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


OPUS_DELAY_FRAMES = 312
ARTIST_CROPS = {"1x1": (512, 512), "9x16": (720, 1280), "16x9": (1280, 720)}   # thumbnails, full screen, wide (hand-off 28)
ARTIST_MAX_BYTES = 25 * 1024 * 1024
ARTIST_SPLIT = re.compile(r"\s*(?:&|,|\+|/|\bfeat\.?|\bft\.?|\bx\b|\band\b|\bb2b\b|\bvs\.?)\s*", re.I)
REEL_TITLE = "Test reel"   # server/make_reel.py: it repeats the other sets, so it stays out of the library
LIBRARY_D_MS = 60000
LIBRARY_NOTICE_S = 600     # how far ahead a changed library takes effect


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
        self.artists_dir = os.path.join(directory, "artists")
        os.makedirs(self.artists_dir, exist_ok=True)
        shutil.rmtree(self.incoming, ignore_errors=True)   # anything here was cut off by a restart
        os.makedirs(self.incoming)
        for s in self.sets():
            if s["status"] in ("queued", "transcoding"):
                self.fail(s["id"], "the server restarted during transcoding, so please upload it again")
        self.rematch()
        self.publish_library()   # for a library from before this list existed
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
        self.publish_library()

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
        self.publish_library()

    # ---- Artist registry: names, other spellings and one photo per artist, cropped three ways round a focus point. Lives only in the
    # library volume and on phones, never in git.

    def artists(self) -> list[dict]:
        out = []
        for a in sorted(os.listdir(self.artists_dir)):
            try:
                with open(os.path.join(self.artists_dir, a, "meta.json")) as f:
                    out.append(json.load(f))
            except (OSError, ValueError):
                pass
        return sorted(out, key=lambda m: m["name"].lower())

    def artist_path(self, artist_id: str, name: str) -> str:
        return os.path.join(self.artists_dir, artist_id, name)

    def add_artist(self, upload: str, name: str, aliases: list[str]) -> str:
        artist_id = uuid.uuid4().hex
        os.makedirs(os.path.join(self.artists_dir, artist_id))
        try:
            photo = self.artist_path(artist_id, "photo.jpg")
            # Re-encode: strips the camera's metadata (location included), turns it upright and caps its size.
            r = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", upload, "-vf", "scale='min(2048,iw)':-2", "-q:v", "3", photo], capture_output=True, text=True)
            if r.returncode != 0:
                raise ValueError("that doesn't look like a photo")
            w, h = image_size(photo)
            self.write_json(self.artist_path(artist_id, "meta.json"), {"id": artist_id, "name": name, "aliases": aliases, "focus": [0.5, 0.4], "w": w, "h": h, "v": 0, "added": time.time()})
            self.crop_artist(artist_id)
        except Exception:
            shutil.rmtree(os.path.join(self.artists_dir, artist_id), ignore_errors=True)
            raise
        finally:
            try:
                os.remove(upload)
            except OSError:
                pass
        self.rematch()
        self.publish_library()
        return artist_id

    def crop_artist(self, artist_id: str):
        with open(self.artist_path(artist_id, "meta.json")) as f:
            m = json.load(f)
        fx, fy = m["focus"]
        for name, (tw, th) in ARTIST_CROPS.items():
            # The largest rectangle of this shape that fits, centred on the focus point as far as the edges allow.
            cw, ch = (m["w"], round(m["w"] * th / tw)) if m["w"] * th / tw <= m["h"] else (round(m["h"] * tw / th), m["h"])
            x = min(max(0, round(fx * m["w"] - cw / 2)), m["w"] - cw)
            y = min(max(0, round(fy * m["h"] - ch / 2)), m["h"] - ch)
            r = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", self.artist_path(artist_id, "photo.jpg"),
                                "-vf", f"crop={cw}:{ch}:{x}:{y},scale={tw}:{th}", "-q:v", "4", self.artist_path(artist_id, name + ".jpg")], capture_output=True, text=True)
            if r.returncode != 0:
                raise ValueError("couldn't crop the photo: " + r.stderr[:200])
        m["v"] = int(time.time())
        self.write_json(self.artist_path(artist_id, "meta.json"), m)

    def set_focus(self, artist_id: str, fx: float, fy: float):
        path = self.artist_path(artist_id, "meta.json")
        with open(path) as f:
            m = json.load(f)
        m["focus"] = [min(max(fx, 0.0), 1.0), min(max(fy, 0.0), 1.0)]
        self.write_json(path, m)
        self.crop_artist(artist_id)
        self.publish_library()

    def update_artist(self, artist_id: str, name: str, aliases: list[str]):
        path = self.artist_path(artist_id, "meta.json")
        with open(path) as f:
            m = json.load(f)
        m["name"], m["aliases"] = name, aliases
        self.write_json(path, m)
        self.rematch()
        self.publish_library()

    def delete_artist(self, artist_id: str):
        shutil.rmtree(os.path.join(self.artists_dir, artist_id))
        for s in self.sets():
            if artist_id in s.get("artist_ids", []):
                self.update(s["id"], artist_ids=[a for a in s["artist_ids"] if a != artist_id], artist_sure=False)
        self.rematch()
        self.publish_library()

    def match(self, text: str) -> tuple[list[str], bool]:
        """Registry artists named in a set's artist field (or its title before " - "), and whether every match was exact."""
        key = lambda t: re.sub(r"[^a-z0-9]+", "", t.lower())
        names = {}
        for a in self.artists():
            for n in [a["name"]] + a.get("aliases", []):
                names[key(n)] = a["id"]
        whole = key(text)
        if whole in names:
            return [names[whole]], True
        near = difflib.get_close_matches(whole, list(names), n=1, cutoff=0.85) if whole else []
        if near:   # a typo in the whole name, such as "Lynnnic": a match to confirm
            return [names[near[0]]], False
        ids, sure = [], True
        for part in ARTIST_SPLIT.split(text):
            k = key(part)
            if not k:
                continue
            if k in names:
                hit = names[k]
            else:
                close = difflib.get_close_matches(k, list(names), n=1, cutoff=0.8)
                if not close:
                    continue
                hit, sure = names[close[0]], False
            if hit not in ids:
                ids.append(hit)
        return ids, sure and bool(ids)

    def match_title(self, title: str) -> tuple[list[str], bool]:
        """Artists named in a title: one part of "Artist - Title", "Title - Artist" or "Title | Artist", or a title that starts with the name."""
        for part in re.split(r"\s+[-–]\s+|\s*[|｜]\s*", title):
            ids, sure = self.match(part)
            if ids:
                return ids, sure
        key = lambda t: re.sub(r"[^a-z0-9]+", "", t.lower())
        for a in self.artists():
            for n in [a["name"]] + a.get("aliases", []):
                if len(key(n)) >= 4 and key(title).startswith(key(n)):
                    return [a["id"]], True
        return [], False

    def rematch(self):
        """Matches every set the user hasn't confirmed by hand."""
        for s in self.sets():
            if s.get("artist_confirmed"):
                continue
            ids, sure = self.match(s["artist"]) if s.get("artist") else ([], False)
            if not ids:
                ids, sure = self.match_title(s.get("title") or "")
            if ids != s.get("artist_ids") or sure != s.get("artist_sure"):
                self.update(s["id"], artist_ids=ids, artist_sure=sure)

    def confirm_artists(self, set_id: str, ids: list[str]):
        known = {a["id"] for a in self.artists()}
        self.update(set_id, artist_ids=[i for i in ids if i in known], artist_sure=True, artist_confirmed=True)
        self.publish_library()

    def publish_library(self):
        """The list phones loop and keep. A change takes effect at a set time in the future, so phones all switch together."""
        items = [{"id": m["id"], "title": m["title"], "artist": m["artist"], "bytes": m["opus_bytes"], "sha256": m["sha256"],
                  # Opus delays the audio by 312 samples: the extra keeps the end of the set, and items are whole seconds.
                  "seconds": math.ceil((m["frames"] + OPUS_DELAY_FRAMES) / 48000)}
                 for m in sorted(self.sets(), key=lambda m: m["added"]) if m["status"] == "ready" and m["title"] != REEL_TITLE]
        path = os.path.join(self.directory, "library.json")
        with self.lock:
            try:
                with open(path) as f:
                    editions = json.load(f)["editions"]
            except (OSError, ValueError, KeyError):
                editions = []
            # Artists ride beside the editions, so a new photo or a confirmed match never moves the shared timeline.
            artists = {a["id"]: {"name": a["name"], "v": a["v"]} for a in self.artists() if a.get("v")}
            set_artists = {m["id"]: m["artist_ids"] for m in self.sets() if m["status"] == "ready" and m.get("artist_ids") and m.get("artist_sure")}
            if (editions[-1]["items"] if editions else []) != items:
                now = time.time_ns() // 1_000_000_000
                start = now + (LIBRARY_NOTICE_S if editions else 1)
                editions = editions[-1:] + [{"start_ns": start * 1_000_000_000, "items": items}]
            new = {"version": 1, "D_ms": LIBRARY_D_MS, "editions": editions, "artists": artists, "set_artists": set_artists}
            try:
                with open(path) as f:
                    if json.load(f) == new:
                        return
            except (OSError, ValueError):
                pass
            self.write_json(path, new)


def image_size(path: str) -> tuple[int, int]:
    r = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "json", path], capture_output=True, text=True)
    s = json.loads(r.stdout)["streams"][0]
    return int(s["width"]), int(s["height"])


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
.artist { display: flex; gap: 12px; align-items: flex-start; } .artist img { border-radius: 8px; } .crops img { height: 72px; margin-right: 6px; }
.photo { position: relative; display: inline-block; cursor: crosshair; } .photo img { max-width: 220px; display: block; }
.dot { position: absolute; width: 14px; height: 14px; margin: -7px; border: 2px solid #cf5; border-radius: 50%; pointer-events: none; }
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
<h2>Artists</h2>
<p class="muted">Names and photos stay on this server and the phones, never in git. Tap a photo where the face is: phones crop round that point.</p>
<form id="aform">
  <label>Name<input type="text" id="aname" maxlength="200" required></label>
  <label>Other spellings (optional, separated by ;)<input type="text" id="aalias" maxlength="200"></label>
  <label>Photo<input type="file" id="afile" accept="image/*" required></label>
  <button id="ago">Add artist</button>
  <p id="amessage" role="status"></p>
</form>
<div id="artists"></div>
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
let artistsShown = "";
function showArtists(artists) {
  const sig = JSON.stringify(artists.map(a => [a.id, a.v, a.name, a.aliases, a.focus]));
  if (sig === artistsShown) return; artistsShown = sig;   // rebuilding every 2 s would reload the photos
  const box = $("artists"); box.replaceChildren();
  if (!artists.length) box.append(el("p", "No artists yet", "muted"));
  for (const a of artists) {
    const row = el("div", null, "set artist");
    const ph = el("div", null, "photo"); const img = el("img"); img.src = "/api/artists/" + a.id + "/photo.jpg?v=" + a.v; ph.append(img);
    const dot = el("div", null, "dot"); dot.style.left = (a.focus[0] * 100) + "%"; dot.style.top = (a.focus[1] * 100) + "%"; ph.append(dot);
    ph.onclick = e => { const r = img.getBoundingClientRect(); act("/api/artists/" + a.id + "/focus?x=" + ((e.clientX - r.left) / r.width).toFixed(3) + "&y=" + ((e.clientY - r.top) / r.height).toFixed(3), "POST"); };
    const info = el("div");
    info.append(el("p", a.name, "title"));
    if (a.aliases && a.aliases.length) info.append(el("p", "Also: " + a.aliases.join("; "), "muted"));
    const crops = el("div", null, "crops");
    for (const c of ["1x1", "9x16", "16x9"]) { const i = el("img"); i.src = "/api/artists/" + a.id + "/" + c + ".jpg?v=" + a.v; i.alt = c; crops.append(i); }
    info.append(crops);
    const ren = el("button", "Rename"); ren.onclick = () => { const n = prompt("Name", a.name); if (n === null) return;
      const al = prompt("Other spellings, separated by ;", (a.aliases || []).join("; ")); act("/api/artists/" + a.id + "?" + new URLSearchParams({name: n, aliases: al || ""}), "POST"); };
    const del = el("button", "Delete"); del.onclick = () => { if (confirm("Delete " + a.name + "?")) act("/api/artists/" + a.id, "DELETE"); };
    info.append(ren, del); row.append(ph, info); box.append(row);
  }
}
function artistLine(s, artists, box) {
  const byId = Object.fromEntries(artists.map(a => [a.id, a.name]));
  const ids = s.artist_ids || [];
  const line = el("p", ids.length ? "Artists: " + ids.map(i => byId[i] || "?").join(", ") + (s.artist_sure ? "" : " · not sure") : "Artists: no match", s.artist_sure ? "muted" : "warn");
  box.append(line);
  if (!artists.length) return;
  const pick = el("select"); pick.multiple = true; pick.size = Math.min(4, artists.length);
  for (const a of artists) { const o = el("option", a.name); o.value = a.id; o.selected = ids.includes(a.id); pick.append(o); }
  const ok = el("button", s.artist_sure ? "Change artists" : "Confirm artists");
  ok.onclick = () => act("/api/sets/" + s.id + "/artists?ids=" + [...pick.selectedOptions].map(o => o.value).join(","), "POST");
  box.append(pick, ok);
}
async function refresh() {
  const r = await fetch("/api/sets"); const d = await r.json();
  showArtists(d.artists || []);
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
    if (s.status === "ready") artistLine(s, d.artists || [], box);
    if (s.added) box.append(el("p", "Uploaded " + new Date(s.added * 1000).toLocaleString([], {dateStyle: "medium", timeStyle: "short"}), "muted"));
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
$("aform").onsubmit = e => {
  e.preventDefault();
  const file = $("afile").files[0]; if (!file) return;
  const q = new URLSearchParams({name: $("aname").value, aliases: $("aalias").value});
  $("ago").disabled = true; $("amessage").textContent = "Uploading…";
  fetch("/api/artists?" + q, {method: "PUT", headers, body: file}).then(async r => {
    const j = await r.json().catch(() => ({})); $("ago").disabled = false;
    $("amessage").textContent = r.ok ? "Added. Tap the photo where the face is." : (j.error || "That didn't work");
    if (r.ok) $("aform").reset(); refresh();
  });
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
                self.reply(sets=lib.sets(), on_air=lib.on_air(), free=lib.free(), used=lib.used(), max=lib.max, reserve=lib.reserve, artists=lib.artists())
            elif m := re.fullmatch(r"/api/artists/([0-9a-f]{32})/(photo|1x1|9x16|16x9)\.jpg", path):
                try:
                    with open(lib.artist_path(m.group(1), m.group(2) + ".jpg"), "rb") as f:
                        self.send(200, f.read(), "image/jpeg")
                except OSError:
                    self.reply(404, error="no such photo")
            else:
                self.reply(404, error="not found")

        def do_PUT(self):
            if not self.allowed(True):
                return
            url = urlparse(self.path)
            if url.path not in ("/api/upload", "/api/artists"):
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
            if url.path == "/api/artists" and (length > ARTIST_MAX_BYTES or not field("name")):
                self.close_connection = True
                self.reply(400, error="an artist needs a name and a photo under 25 MB")
                return
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
            if url.path == "/api/artists":
                try:
                    self.reply(id=lib.add_artist(temp, field("name"), [a.strip() for a in field("aliases").split(";") if a.strip()]))
                except (OSError, ValueError) as e:
                    self.reply(400, error=str(e))
                return
            set_id = lib.add(temp, field("name") or "upload", field("title"), field("artist"), field("set"))
            self.reply(id=set_id)

        def do_POST(self):
            if not self.allowed(True):
                return
            url = urlparse(self.path)
            q = parse_qs(url.query)
            field = lambda k: (q.get(k) or [""])[0].strip()[:200]
            try:
                if a := re.fullmatch(r"/api/artists/([0-9a-f]{32})/focus", url.path):
                    lib.set_focus(a.group(1), float(field("x")), float(field("y")))
                    return self.reply()
                if a := re.fullmatch(r"/api/artists/([0-9a-f]{32})", url.path):
                    if not field("name"):
                        return self.reply(400, error="an artist needs a name")
                    lib.update_artist(a.group(1), field("name"), [x.strip() for x in field("aliases").split(";") if x.strip()])
                    return self.reply()
                if a := re.fullmatch(r"/api/sets/([0-9a-f]{32})/artists", url.path):
                    lib.confirm_artists(a.group(1), [i for i in field("ids").split(",") if ID.match(i)])
                    return self.reply()
            except (OSError, ValueError) as e:
                return self.reply(400, error=str(e))
            m = re.fullmatch(r"/api/sets/([0-9a-f]{32})/air", url.path)
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
            if a := re.fullmatch(r"/api/artists/([0-9a-f]{32})", path):
                try:
                    lib.delete_artist(a.group(1))
                    self.reply()
                except OSError as e:
                    self.reply(400, error=str(e))
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
