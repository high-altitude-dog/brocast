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
# Station news bulletin (hand-off 49). The BBC feed URL carries a key, so it comes from station.json in the data
# folder ("news_urls": {"bbc": "..."}) or BROCAST_NEWS_BBC_URL, never from this public file.
NEWS_CHOICES = {"bbc": "BBC World Service", "npr": "NPR News Now", "none": "None"}
# Station colour (hand-offs 43 and 50): the 21 accents, Lime by default. Anything else reads as Lime on the phones.
PALETTE = {"Lime": "#C8FF4D", "Chartreuse": "#DEFF5C", "Lemon": "#F4F56A", "Pistachio": "#B8F2A0", "Spring": "#7DFF7A", "Mint": "#6BFFB8", "Seafoam": "#5CF2D0",
           "Ice": "#A6E4FF", "Sky": "#7CC8FF", "Bluebell": "#86A8FF", "Hyacinth": "#A2A6FF", "Lavender": "#BCA8FF", "Purple": "#A98BFF", "Violet": "#C483FF",
           "Orchid": "#DE8CFF", "Lilac": "#E6B3FF", "Magenta": "#FF7AE0", "Pink": "#FF8FC8", "Bubblegum": "#FFB0D9", "Blush": "#FFC4CF", "Iris": "#9E95FF"}
NEWS_PUBLIC_URLS = {"npr": "https://feeds.npr.org/500005/podcast.xml"}
LIBRARY_NOTICE_S = 600     # how far ahead a new running order takes effect at the earliest, so every phone can fetch it
MESSAGE_MAX = 140
SEC = 1_000_000_000


# ---- The running order (hand-off 41). An edition is one running order from start_ns on the shared timeline, heard at start_ns + D;
# from_s is the loop position it starts at, so a change at a set boundary carries on with the set after the one that ended, not set 1.
# All times here are timeline times, in ns, whole seconds.

def loop_s(e: dict) -> int:
    return sum(i["seconds"] for i in e["items"])


def edition_at(eds: list[dict], t: int) -> dict | None:
    """The newest edition already started at t, or the earliest if none has (as LibraryPlan.kt's editionAt)."""
    started = [e for e in eds if e["start_ns"] <= t]
    return max(started, key=lambda e: e["start_ns"]) if started else min(eds, key=lambda e: e["start_ns"], default=None)


def parse_colors(q: dict) -> dict:
    """`id:HEX,id:HEX` from the page: per-set colours."""
    out = {}
    for pair in (q.get("colors") or [""])[0].split(","):
        k, _, v = pair.partition(":")
        if ID.match(k) and ("#" + v.lstrip("#").upper()) in PALETTE.values():
            out[k] = "#" + v.lstrip("#").upper()
    return out


def item_at(e: dict, t: int) -> tuple[int, int] | None:
    """The index of the set playing at t in edition e, and how many seconds into it."""
    total = loop_s(e)
    if total <= 0:
        return None
    left = ((t - e["start_ns"]) // SEC + e.get("from_s", 0)) % total
    for k, i in enumerate(e["items"]):
        if left < i["seconds"]:
            return k, left
        left -= i["seconds"]
    return None


def next_boundary(eds: list[dict], after: int) -> int:
    """The first set start at or after [after]: within the edition playing then, or an edition's own start."""
    t = -(-after // SEC) * SEC
    e = edition_at(eds, t)
    if e is None or t < e["start_ns"]:
        return t if e is None else e["start_ns"]
    k, off = item_at(e, t)
    b = t if off == 0 else t + (e["items"][k]["seconds"] - off) * SEC
    switch = min((x["start_ns"] for x in eds if x["start_ns"] > t), default=None)
    return min(b, switch) if switch is not None else b


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

    def add(self, upload: str, filename: str, title: str, artist: str, set_name: str, goes: str = "end") -> str:
        set_id = uuid.uuid4().hex
        os.makedirs(os.path.join(self.sets_dir, set_id))
        self.write_json(self.path(set_id, "meta.json"), {
            "id": set_id, "status": "queued", "added": time.time(), "filename": filename,
            "title": title, "artist": artist, "set": set_name, "goes": "next" if goes == "next" else "end",
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
        if title != REEL_TITLE:
            self.join_draft(set_id, m.get("goes", "end"))

    def ready_items(self) -> dict[str, dict]:
        """Every set phones can play, as an edition lists it."""
        return {m["id"]: {"id": m["id"], "title": m["title"], "artist": m["artist"], "bytes": m["opus_bytes"], "sha256": m["sha256"],
                          # Opus delays the audio by 312 samples: the extra keeps the end of the set, and items are whole seconds.
                          "seconds": math.ceil((m["frames"] + OPUS_DELAY_FRAMES) / 48000)}
                for m in self.sets() if m["status"] == "ready" and m["title"] != REEL_TITLE}

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
            raise ValueError("this set is on the test stream, so take it off first")
        if self.meta(set_id)["status"] in ("queued", "transcoding"):
            raise ValueError("this set is still transcoding")
        if any(i["id"] == set_id for e in self.read_library()["editions"] for i in e["items"]):
            raise ValueError("this set is in the running order: take it out and publish first")
        shutil.rmtree(os.path.join(self.sets_dir, set_id))
        draft = self.draft()
        if draft and set_id in draft:
            self.save_draft([i for i in draft if i != set_id])

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

    # ---- Running order and editions (hand-off 41)

    def read_library(self) -> dict:
        try:
            with open(os.path.join(self.directory, "library.json")) as f:
                lib = json.load(f)
        except (OSError, ValueError):
            lib = {}
        eds = lib.get("editions") or []
        for k, e in enumerate(eds):   # editions from before they were numbered
            e.setdefault("n", k + 1)
        return {"editions": eds, "called_off": lib.get("called_off") or []}

    # ---- Station settings (hand-off 49: news bulletin)

    def station(self) -> dict:
        try:
            with open(os.path.join(self.directory, "station.json")) as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def news(self) -> dict:
        """The station's bulletin as it goes to phones: choice, name and feed URL ('' when not set up)."""
        st = self.station()
        choice = st.get("news") if st.get("news") in NEWS_CHOICES else "bbc"
        url = (st.get("news_urls") or {}).get(choice) or (os.environ.get("BROCAST_NEWS_BBC_URL", "") if choice == "bbc" else NEWS_PUBLIC_URLS.get(choice, ""))
        return {"choice": choice, "name": NEWS_CHOICES[choice], "url": url}

    def colour(self) -> dict:
        """The station colour as phones get it: hex, the one before, and when it took or takes over at the ear."""
        st = self.station()
        hexv = st.get("color") if st.get("color") in PALETTE.values() else PALETTE["Lime"]
        prev = st.get("color_prev") if st.get("color_prev") in PALETTE.values() else hexv
        return {"hex": hexv, "prev": prev, "at_ns": int(st.get("color_at_ns") or 0), "tell": bool(st.get("color_tell"))}

    def set_colour(self, hexv: str, when: str, tell: bool):
        if hexv not in PALETTE.values():
            raise ValueError("pick one of the 21 colours")
        with self.lock:
            st = self.station()
            eds = self.read_library()["editions"]
            heard = time.time_ns()
            if when == "next" and eds:
                heard = next_boundary(eds, self.timeline_now() + SEC) + LIBRARY_D_MS * 1_000_000
            st.update(color_prev=self.colour()["hex"], color=hexv, color_at_ns=heard, color_tell=tell)
            self.write_json(os.path.join(self.directory, "station.json"), st)
        self.publish_library()

    def set_news(self, choice: str):
        if choice not in NEWS_CHOICES:
            raise ValueError("no such news choice")
        with self.lock:
            st = self.station()
            st["news"] = choice
            self.write_json(os.path.join(self.directory, "station.json"), st)
        self.publish_library()

    def write_library(self, editions: list[dict], called_off: list[dict]):
        """Writes library.json. Artists ride beside the editions, so a new photo or a confirmed match never moves the shared timeline."""
        artists = {a["id"]: {"name": a["name"], "v": a["v"]} for a in self.artists() if a.get("v")}
        set_artists = {m["id"]: m["artist_ids"] for m in self.sets() if m["status"] == "ready" and m.get("artist_ids") and m.get("artist_sure")}
        new = {"version": 1, "D_ms": LIBRARY_D_MS, "editions": editions, "called_off": called_off[-8:], "artists": artists, "set_artists": set_artists, "news": self.news(), "color": self.colour()}
        path = os.path.join(self.directory, "library.json")
        try:
            with open(path) as f:
                if json.load(f) == new:
                    return
        except (OSError, ValueError):
            pass
        self.write_json(path, new)

    def publish_library(self):
        """Keeps library.json current: the artists, and the very first edition once there's a set. Later orders are published by hand."""
        with self.lock:
            lib = self.read_library()
            eds = lib["editions"]
            if not eds:
                items = sorted(self.ready_items().values(), key=lambda i: self.meta(i["id"])["added"])
                if items:
                    eds = [{"n": 1, "start_ns": (time.time_ns() // SEC + 1) * SEC, "from_s": 0, "items": items, "message": "", "published_ns": time.time_ns()}]
            self.write_library(eds, lib["called_off"])

    def timeline_now(self) -> int:
        """The timeline position heard now: phones hear start_ns at start_ns + D."""
        return time.time_ns() - LIBRARY_D_MS * 1_000_000

    def running(self) -> dict:
        """The live edition, the queued one if any, and whether it's locked (within the last 10 minutes)."""
        eds = self.read_library()["editions"]
        live = edition_at(eds, self.timeline_now())
        queued = max((e for e in eds if live and e["start_ns"] > live["start_ns"]), key=lambda e: e["start_ns"], default=None)
        locked = bool(queued) and queued["start_ns"] - self.timeline_now() < LIBRARY_NOTICE_S * SEC
        return {"live": live, "queued": queued, "locked": locked}

    def draft_colors(self) -> dict | None:
        """Per-set colours in the draft (hand-off 50), or None to keep what's published."""
        try:
            with open(os.path.join(self.directory, "draft.json")) as f:
                c = json.load(f).get("colors")
                return {k: v for k, v in c.items() if v in PALETTE.values()} if isinstance(c, dict) else None
        except (OSError, ValueError):
            return None

    def draft(self) -> list[str] | None:
        try:
            with open(os.path.join(self.directory, "draft.json")) as f:
                return json.load(f)["order"]
        except (OSError, ValueError, KeyError):
            return None

    def save_draft(self, order: list[str] | None, colors: dict | None = None):
        path = os.path.join(self.directory, "draft.json")
        if colors is None and order is not None:
            colors = self.draft_colors()
        if order is None:
            try:
                os.remove(path)
            except OSError:
                pass
        else:
            self.write_json(path, {"order": order, **({"colors": colors} if colors is not None else {})})

    def current_order(self) -> list[str]:
        r = self.running()
        e = r["queued"] or r["live"]
        return [i["id"] for i in e["items"]] if e else []

    def join_draft(self, set_id: str, goes: str):
        """A finished upload joins the draft order, at the end or straight after the set on air. It reaches listeners only when published."""
        if not self.read_library()["editions"]:
            return   # the very first edition takes it
        order = [i for i in (self.draft() or self.current_order()) if i != set_id]
        live = self.running()["live"]
        at = len(order)
        if goes == "next" and live:
            k, _ = item_at(live, self.timeline_now()) or (None, 0)
            playing = live["items"][k]["id"] if k is not None else None
            if playing in order:
                at = order.index(playing) + 1
        order.insert(at, set_id)
        self.save_draft(order)

    def plan(self, replacing: bool = True) -> dict:
        """The default start for a new order: the next set boundary at least 10 minutes away, never cutting a set off."""
        r = self.running()
        eds = self.read_library()["editions"]
        earliest = self.timeline_now() + LIBRARY_NOTICE_S * SEC
        q = r["queued"]
        if q and not r["locked"] and replacing:
            base = [e for e in eds if e["n"] != q["n"]]
            start = q["start_ns"]   # a change before it starts keeps its time while that's still 10 minutes away
        else:
            base = eds
            start = next_boundary(eds, max(earliest, q["start_ns"] + SEC) if q else earliest)
        before = edition_at(base, start - SEC)
        k = item_at(before, start - SEC) if before else None
        return {"start_ns": start, "ending": before["items"][k[0]]["title"] if k else None, "earliest_ns": earliest}

    def publish(self, order: list[str], when: str, at_heard_ns: int | None, message: str, colors: dict | None = None) -> dict:
        ready = self.ready_items()
        colors = {k: v for k, v in (colors or {}).items() if v in PALETTE.values()}
        if not order or any(i not in ready for i in order) or len(set(order)) != len(order):
            raise ValueError("the order must list each ready set once")
        if len(message) > MESSAGE_MAX:
            raise ValueError(f"the message is limited to {MESSAGE_MAX} characters")
        with self.lock:
            lib = self.read_library()
            eds, r = lib["editions"], self.running()
            q = r["queued"]
            replacing = bool(q) and not r["locked"]
            base = [e for e in eds if not (replacing and e["n"] == q["n"])]
            earliest = self.timeline_now() + LIBRARY_NOTICE_S * SEC
            if when == "time":
                if at_heard_ns is None:
                    raise ValueError("a set time needs a time")
                start = -(-(at_heard_ns - LIBRARY_D_MS * 1_000_000) // SEC) * SEC
                if start < earliest:
                    raise ValueError("a set time must be at least 10 minutes away, so every phone can fetch the new order")
                if q and r["locked"] and start <= q["start_ns"]:
                    raise ValueError("the queued order is locked: a newer one goes in after it")
            else:
                start = self.plan()["start_ns"]
            # Carry on with the set after the one playing just before the change, so a boundary change doesn't jump back to set 1.
            before = edition_at(base, start - SEC)
            k = item_at(before, start - SEC) if before else None
            was = before["items"][k[0]]["id"] if k else None
            nxt = (order.index(was) + 1) % len(order) if was in order else 0
            items = [{**ready[i], **({"color": colors[i]} if i in colors else {})} for i in order]
            from_s = sum(i["seconds"] for i in items[:nxt])
            live = edition_at(base, self.timeline_now())
            keep = [e for e in base if live is None or e["start_ns"] >= live["start_ns"]]
            n = max([e["n"] for e in eds] + [c.get("n", 0) for c in lib["called_off"]] + [0]) + 1
            new = {"n": n, "start_ns": start, "from_s": from_s, "items": items, "message": message.strip(), "published_ns": time.time_ns()}
            self.write_library(keep + [new], lib["called_off"])
        self.save_draft(None)
        return new

    def edit_message(self, message: str):
        if len(message) > MESSAGE_MAX:
            raise ValueError(f"the message is limited to {MESSAGE_MAX} characters")
        with self.lock:
            r, lib = self.running(), self.read_library()
            if not r["queued"] or r["locked"]:
                raise ValueError("there's no queued order to change, or it's locked")
            for e in lib["editions"]:
                if e["n"] == r["queued"]["n"]:
                    e["message"] = message.strip()
            self.write_library(lib["editions"], lib["called_off"])

    def cancel_queued(self):
        """Withdraws the queued order. Phones that fetched it drop it and say it was called off."""
        with self.lock:
            r, lib = self.running(), self.read_library()
            q = r["queued"]
            if not q or r["locked"]:
                raise ValueError("there's no queued order to cancel, or it's locked")
            self.write_library([e for e in lib["editions"] if e["n"] != q["n"]], lib["called_off"] + [{"n": q["n"], "start_ns": q["start_ns"]}])


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
<title>Station · Beach Sync Radio</title>
<style>
:root { color-scheme: dark; --bg: #0A0C0F; --surface: #12161B; --border: #222932; --track: #1A1F26; --grid: #2A323C; --text: #ECEFF2;
  --text2: #C4CBD2; --muted: #8B95A1; --faint: #4A5562; --accent: #C8FF4D; --news: #56D4DD; --on-accent: #0A0C0F; --warn: #FFB547; --danger: #FF5A5A;
  --mono: 'JetBrains Mono', ui-monospace, monospace; font: 15px/1.45 'Space Grotesk', system-ui, sans-serif; }
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text); display: grid; grid-template-columns: 230px minmax(0, 1fr) 360px; min-height: 100vh; }
button, input, select, textarea { font: inherit; color: inherit; }
button { cursor: pointer; background: none; border: 1px solid var(--grid); border-radius: 999px; padding: 8px 16px; min-height: 40px; }
button.primary { background: var(--accent); color: var(--on-accent); border-color: var(--accent); font-weight: 600; }
button.danger { color: var(--danger); border-color: var(--danger); } button:disabled { opacity: .45; cursor: default; }
input[type=text], input[type=time], select, textarea { background: var(--bg); border: 1px solid var(--grid); border-radius: 10px; padding: 10px 12px; width: 100%; }
.label { font-size: 11px; letter-spacing: .16em; text-transform: uppercase; color: var(--muted); font-weight: 600; }
.mono { font-family: var(--mono); } .muted { color: var(--muted); } .small { font-size: 13px; } .bad { color: var(--danger); } .warn { color: var(--warn); }
nav { border-right: 1px solid var(--border); padding: 24px 12px; display: flex; flex-direction: column; gap: 4px; }
nav .brand { padding: 0 12px 16px; } nav a { display: block; padding: 10px 12px; border-radius: 10px; color: var(--text); text-decoration: none; cursor: pointer; }
nav a.on { background: var(--surface); font-weight: 600; } nav a span { display: block; font-size: 12px; color: var(--muted); font-weight: 400; }
nav .foot { margin-top: auto; padding: 12px; font-size: 12px; color: var(--muted); }
main { padding: 28px 36px; min-width: 0; } aside { border-left: 1px solid var(--border); padding: 24px 20px; display: flex; flex-direction: column; gap: 16px; }
h1 { font-size: 30px; margin: 0; display: inline; } .head { display: flex; align-items: baseline; gap: 18px; flex-wrap: wrap; margin-bottom: 18px; }
.bar { display: flex; align-items: center; gap: 16px; border-radius: 14px; padding: 12px 16px; margin-bottom: 14px; }
.bar.draft { border: 1px solid var(--warn); background: rgba(255, 181, 71, .06); } .bar.queued { border: 1px solid var(--accent); background: rgba(200, 255, 77, .05); }
.bar.locked { border: 1px solid var(--grid); background: var(--surface); } .bar .text { flex: 1; }
.chip { font-family: var(--mono); font-size: 12px; font-weight: 700; letter-spacing: .08em; padding: 3px 8px; border-radius: 4px; }
.chip.draft { background: var(--warn); color: var(--on-accent); } .chip.queued { background: var(--accent); color: var(--on-accent); } .chip.locked { background: var(--grid); }
table { width: 100%; border-collapse: collapse; } th { text-align: left; font-weight: 600; padding: 8px; } td { padding: 10px 8px; border-bottom: 1px solid var(--border); vertical-align: middle; }
tr.moved td { background: rgba(255, 181, 71, .06); } tr.moved td:first-child { box-shadow: inset 2px 0 var(--warn); } tr.over td { border-top: 2px solid var(--accent); }
.handle { cursor: grab; color: var(--muted); font-size: 18px; user-select: none; } .handle.fixed { cursor: not-allowed; opacity: .3; }
.thumb { width: 44px; height: 44px; border-radius: 8px; background: var(--track); object-fit: cover; display: grid; place-items: center; font-family: var(--mono); font-weight: 700; }
.set-title { font-weight: 600; font-size: 16px; } .tag-moved { color: var(--warn); font-family: var(--mono); font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.st-played { color: var(--faint); } .st-air { color: var(--accent); } .dots { border: 0; min-height: 0; padding: 4px 10px; color: var(--muted); font-size: 18px; }
.menu { position: absolute; background: var(--surface); border: 1px solid var(--grid); border-radius: 10px; padding: 6px; z-index: 5; min-width: 200px; }
.menu div { padding: 8px 12px; border-radius: 6px; cursor: pointer; } .menu div:hover { background: var(--track); } .menu .off { color: var(--faint); cursor: default; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 16px; padding: 18px; display: flex; flex-direction: column; gap: 12px; }
.card .row { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
.drop { border: 1px dashed var(--grid); border-radius: 12px; padding: 22px; text-align: center; cursor: pointer; } .drop.hot { border-color: var(--accent); }
.steps { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; } .steps div { height: 4px; border-radius: 2px; background: var(--track); overflow: hidden; } .steps i { display: block; height: 100%; background: var(--accent); }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; } .grid2 label { font-size: 13px; color: var(--muted); display: flex; flex-direction: column; gap: 4px; }
.disk { height: 8px; border-radius: 4px; background: var(--track); display: flex; overflow: hidden; } .disk i { display: block; height: 100%; }
.confirm { border: 1px solid var(--warn); border-radius: 12px; padding: 10px 12px; display: flex; align-items: center; gap: 10px; }
.confirm b { font-weight: 600; } .confirm a { color: var(--accent); cursor: pointer; } .confirm a + a { color: var(--text); }
.faces { display: flex; gap: 8px; align-items: center; } .faces img { width: 44px; height: 44px; border-radius: 8px; object-fit: cover; }
dialog { background: var(--surface); color: var(--text); border: 1px solid var(--grid); border-radius: 20px; padding: 28px; width: min(900px, 94vw); }
dialog::backdrop { background: rgba(0, 0, 0, .7); } .cols { display: grid; grid-template-columns: 1fr 40px 1fr; align-items: center; }
.cols ol { list-style: none; padding: 0; margin: 6px 0; } .cols li { padding: 8px 12px; border-radius: 8px; } .cols li.hl { background: rgba(255, 181, 71, .12); color: var(--warn); }
.cols li b { font-family: var(--mono); color: var(--muted); font-weight: 400; margin-right: 12px; font-size: 13px; }
.opt { border: 1px solid var(--grid); border-radius: 12px; padding: 12px 14px; display: flex; gap: 12px; align-items: flex-start; margin-top: 8px; cursor: pointer; }
.opt.on { border-color: var(--accent); } .opt input { accent-color: var(--accent); margin-top: 4px; }
.swatches { display: grid; grid-template-columns: repeat(7, 1fr); gap: 8px; margin: 12px 0; } .sw { aspect-ratio: 1; border-radius: 50%; border: 0; padding: 0; min-height: 0; cursor: pointer; display: flex; align-items: center; justify-content: center; color: #0A0C0F; font-weight: 700; }
.sw.cur { box-shadow: 0 0 0 3px var(--bg, #0A0C0F), 0 0 0 5px #fff; } .cdot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; vertical-align: -1px; } .cring { display: inline-block; width: 8px; height: 8px; border-radius: 50%; border: 2px solid var(--accent); margin-right: 6px; vertical-align: -1px; }
.colpop { position: absolute; z-index: 20; background: #15191E; border: 1px solid var(--grid); border-radius: 12px; padding: 14px; width: 300px; } .colpop .swatches { margin: 10px 0 6px; } .colcell { cursor: pointer; white-space: nowrap; }
.preview { display: flex; gap: 14px; margin: 12px 0; } .preview div { flex: 1; border: 1px solid var(--grid); border-radius: 12px; padding: 12px; } .preview .pill { border-radius: 99px; padding: 8px 12px; color: #0A0C0F; font-weight: 600; margin-top: 8px; text-align: center; }
.opt.news.on { border-color: var(--news); } .opt.news input { accent-color: var(--news); } .faint { color: var(--faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 300px; }
.artist { display: flex; gap: 12px; align-items: flex-start; border: 1px solid var(--border); border-radius: 12px; padding: 12px; margin: 8px 0; }
.photo { position: relative; display: inline-block; cursor: crosshair; } .photo img { max-width: 200px; display: block; border-radius: 8px; }
.dot { position: absolute; width: 14px; height: 14px; margin: -7px; border: 2px solid var(--accent); border-radius: 50%; pointer-events: none; } .crops img { height: 64px; margin-right: 6px; border-radius: 6px; }
@media (max-width: 1100px) { body { grid-template-columns: 1fr; } nav { flex-direction: row; flex-wrap: wrap; border-right: 0; } aside { border-left: 0; } }
</style></head><body>
<nav>
  <div class="brand label">Beach Sync Radio · Station</div>
  <a id="nav-order" class="on">Running order<span id="nav-order-sub"></span></a>
  <a id="nav-upload">Upload<span id="nav-upload-sub"></span></a>
  <a id="nav-artists">Artists<span id="nav-artists-sub"></span></a>
  <a id="nav-disk">Disk<span id="nav-disk-sub"></span></a>
  <a id="nav-colour">Colour<span id="nav-colour-sub"></span></a>
  <a id="nav-news">News<span id="nav-news-sub"></span></a>
  <div class="foot">Reached through the SSH tunnel only. No login: anyone with the tunnel is the operator.</div>
</nav>
<main>
  <section id="view-order">
    <div class="head"><h1>Running order</h1><span class="muted" id="live"></span></div>
    <div id="bars"></div>
    <table><thead><tr><th></th><th class="label">#</th><th></th><th class="label">Set</th><th class="label">Artist</th><th class="label">Length</th><th class="label">Plays (<span id="tz"></span>)</th><th class="label">Colour</th><th class="label">Status</th><th></th></tr></thead>
      <tbody id="rows"></tbody></table>
    <p class="muted small">Drag a row by its handle, or use ⋯ › Move up / Move down / Move to… Times are for the next loop after publishing. The set on air can't be moved until it ends.</p>
    <div id="out"></div>
    <hr style="border: 0; border-top: 1px solid var(--border); margin: 18px 0">
    <div class="label">Track order · later</div>
    <p class="muted">Once sets have track lists, a set opens to its tracks and they reorder the same way.</p>
  </section>
  <section id="view-artists" hidden>
    <div class="head"><h1>Artists</h1><span class="muted">Names and photos stay on this server and the phones, never in git. Click a photo where the face is: phones crop round that point.</span></div>
    <form id="aform" class="card" style="max-width: 560px">
      <div class="grid2"><label>Name<input type="text" id="aname" maxlength="200" required></label><label>Other spellings (separated by ;)<input type="text" id="aalias" maxlength="200"></label></div>
      <label class="small muted">Photo <input type="file" id="afile" accept="image/*" required></label>
      <div><button class="primary" id="ago">Add artist</button> <span id="amessage" role="status" class="muted"></span></div>
    </form>
    <div id="artists"></div>
  </section>
</main>
<aside>
  <div class="card" id="upload-card">
    <div class="row"><span class="label">Upload</span><span class="muted small">FLAC, WAV, MP3 · up to 4 GB</span></div>
    <div class="drop" id="drop">⤒<br>Drop a set here, or <span style="color: var(--accent)">choose a file</span><input type="file" id="file" hidden accept="audio/*,.flac,.wav,.mp3,.m4a,.aac,.ogg,.opus,.aiff"></div>
    <div id="picked" hidden>
      <div class="row"><span id="fname"></span><span class="mono" id="fsize"></span></div>
      <div class="grid2" style="margin-top: 10px">
        <label>Title<input type="text" id="title" maxlength="200" placeholder="from the file's tags"></label>
        <label>Artist<input type="text" id="artist" maxlength="200" list="artist-names" placeholder="from the artist list"></label>
        <label>Set name<input type="text" id="set" maxlength="200" placeholder="optional"></label>
        <label>Goes<select id="goes"><option value="end">At the end of the order</option><option value="next">Next, after the set on air</option></select></label>
      </div>
      <div style="margin-top: 10px"><button class="primary" id="go">Upload</button> <button id="unpick">Cancel</button></div>
    </div>
    <datalist id="artist-names"></datalist>
    <div id="jobs"></div>
    <p class="muted small">Artist picks from the artist list, so name and photo match; a new name is held for you to confirm. A finished upload joins the draft order; it reaches listeners only when you publish. Only upload music cleared for this.</p>
  </div>
  <div class="card" id="disk-card">
    <div class="row"><span class="label">Disk</span><span class="mono small" id="disk-free"></span></div>
    <div class="disk" id="disk-bar"></div>
    <div class="muted small" id="disk-key"></div>
    <p class="muted small">Checked before an upload starts: if a file wouldn't leave the reserve free, the drop zone says so and doesn't start.</p>
  </div>
  <div class="card" id="artists-card">
    <div class="row"><span class="label">Artists</span><a style="color: var(--accent); cursor: pointer" id="open-artists">Open artists ›</a></div>
    <div class="faces" id="faces"></div>
    <div id="confirms"></div>
  </div>
  <div class="card" id="colour-card">
    <div class="row"><span class="label">Station colour</span><span class="small" id="colour-now"></span></div>
    <div class="swatches" id="swatches"></div>
    <p class="muted small">Like the lights on silent-disco headphones: every listener’s phone takes this colour, so a crowd can see who’s on this station. Pick one to preview it before it goes out.</p>
  </div>
  <div class="card" id="news-card">
    <div class="row"><span class="label">News bulletin</span><span class="small" style="color: var(--news)" id="news-now"></span></div>
    <div id="news-opts"></div>
    <p class="muted small">What listeners hear when they press their headphone button. Each phone fetches it straight from the source; nothing goes through this server. Changes reach phones with the next announcement.</p>
  </div>
</aside>
<dialog id="pub"></dialog>
<dialog id="colour-dlg"></dialog>
<script>
const $ = id => document.getElementById(id);
const headers = {"X-Brocast": "1"};
const gb = n => (n / 1073741824).toFixed(1) + " GB";
const hms = s => { s = Math.round(s); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(x).padStart(2, "0"); };
const hm = ns => new Date(ns / 1e6).toLocaleTimeString([], {hour: "2-digit", minute: "2-digit", hour12: false});
function el(tag, text, cls) { const e = document.createElement(tag); if (text != null) e.textContent = text; if (cls) e.className = cls; return e; }
async function act(url, method) { const r = await fetch(url, {method, headers}); const j = await r.json().catch(() => ({})); if (!r.ok) alert(j.error || "That didn't work"); await refresh(true); return r.ok; }
$("tz").textContent = (Intl.DateTimeFormat().resolvedOptions().timeZone || "local").split("/").pop().replace("_", " ") === "London" ? "UK" : "local";
let d = null, order = null, dragging = false, menu = null, view = "order";
const SEC = 1e9;
// The set at the ear now, in an edition: as LibraryPlan.kt, with its loop position.
function playing(e, tNs) {
  const total = e.items.reduce((a, i) => a + i.seconds, 0); if (!total) return null;
  let left = (((Math.floor((tNs - e.start_ns) / SEC) + (e.from_s || 0)) % total) + total) % total;
  for (let k = 0; k < e.items.length; k++) { if (left < e.items[k].seconds) return {k, off: left}; left -= e.items[k].seconds; }
  return null;
}
// Which rows moved: those outside the longest common run of the two orders.
function movedIds(base, now) {
  const n = base.length, m = now.length, L = Array.from({length: n + 1}, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i][j] = base[i] === now[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
  const keep = new Set(); let i = 0, j = 0;
  while (i < n && j < m) { if (base[i] === now[j]) { keep.add(now[j]); i++; j++; } else if (L[i + 1][j] >= L[i][j + 1]) i++; else j++; }
  return new Set(now.filter(x => !keep.has(x)));
}
const byId = () => Object.fromEntries(d.sets.map(s => [s.id, s]));
let colors = {};
const baseColors = () => Object.fromEntries(((d.queued || d.live) ? (d.queued || d.live).items : []).filter(i => i.color).map(i => [i.id, i.color]));
const sameColors = (a, b) => JSON.stringify(Object.entries(a).sort()) === JSON.stringify(Object.entries(b).sort());
const colourName = hex => Object.keys(d.palette).find(k => d.palette[k] === hex) || "Lime";
const baseOrder = () => ((d.queued || d.live) ? (d.queued || d.live).items.map(i => i.id) : []);
const secondsOf = id => { const s = byId()[id]; return s ? Math.ceil(s.frames / 48000) : 0; };
function onAirId() { if (!d.live) return null; const p = playing(d.live, d.now_ns - d.D_ms * 1e6); return p ? {id: d.live.items[p.k].id, off: p.off} : null; }
const colorParam = c => Object.entries(c).map(([k, v]) => k + ":" + v.slice(1)).join(",");
async function saveDraft(o) { order = o; render(); await fetch("/api/draft?order=" + o.join(",") + "&colors=" + colorParam(colors), {method: "POST", headers}); }
function move(id, to) {
  const o = order.filter(x => x !== id); o.splice(Math.max(0, Math.min(to, o.length)), 0, id);
  const air = onAirId(); if (air && order.indexOf(air.id) !== o.indexOf(air.id)) { alert("The set on air can't move until it ends."); return; }
  saveDraft(o);
}
function closeMenu() { if (menu) { menu.remove(); menu = null; } }
document.addEventListener("click", e => { if (menu && !menu.contains(e.target)) closeMenu(); });
function openMenu(btn, id, inOrder) {
  closeMenu(); menu = el("div", null, "menu"); const r = btn.getBoundingClientRect();
  menu.style.left = (r.right + scrollX - 210) + "px"; menu.style.top = (r.bottom + scrollY) + "px";
  const air = onAirId(), fixed = air && air.id === id, i = order.indexOf(id);
  const opt = (text, ok, f) => { const o = el("div", text, ok ? "" : "off"); if (ok) o.onclick = () => { closeMenu(); f(); }; menu.append(o); };
  if (inOrder) {
    opt("Move up", !fixed && i > 0, () => move(id, i - 1));
    opt("Move down", !fixed && i < order.length - 1, () => move(id, i + 1));
    opt("Move to…", !fixed, () => { const n = parseInt(prompt("Move to position (1–" + order.length + ")", i + 1)); if (n) move(id, n - 1); });
    opt("Take out of the order", !fixed && order.length > 1, () => saveDraft(order.filter(x => x !== id)));
  } else opt("Add to the end of the order", true, () => saveDraft(order.concat([id])));
  const inEdition = [d.live, d.queued].some(e => e && e.items.some(x => x.id === id));
  opt("Put on the test stream", true, () => act("/api/sets/" + id + "/air", "POST"));
  opt("Delete set", !inEdition, () => { if (confirm("Delete this set from the server?")) act("/api/sets/" + id, "DELETE"); });
  document.body.append(menu);
}
function thumb(s) {
  const a = (s.artist_ids || [])[0] && d.artists.find(x => x.id === s.artist_ids[0]);
  if (a && a.v) { const i = el("img", null, "thumb"); i.src = "/api/artists/" + a.id + "/1x1.jpg?v=" + a.v; i.alt = ""; return i; }
  return el("div", (s.artist || s.title || "?").replace(/[^A-Za-z]/g, "").slice(0, 2).toUpperCase(), "thumb");
}
function render() {
  const sets = byId(), base = baseOrder(), moved = movedIds(base, order), air = onAirId();
  const colorsChanged = !sameColors(colors, baseColors());
  const isDraft = (d.draft && JSON.stringify(d.draft) !== JSON.stringify(base)) || colorsChanged;
  // Bars: queued or locked, then the draft.
  const bars = $("bars"); bars.replaceChildren();
  if (d.queued) {
    const q = d.queued, heard = q.start_ns + d.D_ms * 1e6, mins = Math.max(0, Math.round((heard - d.now_ns) / 6e10));
    const b = el("div", null, "bar " + (d.locked ? "locked" : "queued"));
    b.append(el("span", d.locked ? "LOCKED" : "QUEUED", "chip " + (d.locked ? "locked" : "queued")));
    b.append(el("span", "Edition " + q.n + " takes effect at " + hm(heard) + ", in " + mins + " m." + (d.locked ? " Phones are switching; a newer order goes in after it." : " Phones fetching it: not counted yet.") + (q.message ? " “" + q.message + "”" : ""), "text"));
    if (!d.locked) {
      const em = el("button", "Edit message"); em.onclick = () => { const m = prompt("Message to listeners (" + d.message_max + " characters at most)", q.message || ""); if (m !== null) act("/api/queued/message?message=" + encodeURIComponent(m.slice(0, d.message_max)), "POST"); };
      const co = el("button", "Change order"); co.onclick = () => saveDraft(base);
      const ca = el("button", "Cancel edition", "danger"); ca.onclick = () => { if (confirm("Cancel edition " + q.n + "? Phones that fetched it drop it, and listeners see that the change was called off.")) act("/api/queued", "DELETE"); };
      b.append(em, co, ca);
    }
    bars.append(b);
  }
  if (isDraft) {
    const b = el("div", null, "bar draft"); b.append(el("span", "DRAFT", "chip draft"));
    const words = [];
    for (const id of order) if (moved.has(id)) words.push((sets[id] ? sets[id].title : "?") + (base.includes(id) ? (order.indexOf(id) < base.indexOf(id) ? " moved up" : " moved down") : " added"));
    for (const id of base) if (!order.includes(id)) words.push((sets[id] ? sets[id].title : "?") + " taken out");
    const bc = baseColors(); for (const id of order) if ((colors[id] || "") !== (bc[id] || "")) words.push((sets[id] ? sets[id].title : "?") + ": " + (colors[id] ? colourName(colors[id]) : "station colour"));
    b.append(el("span", (words.join(", ") || "Order changed") + ". Nothing changes for listeners until you publish.", "text"));
    const x = el("button", "Discard"); x.onclick = () => act("/api/draft", "DELETE");
    const p = el("button", "Publish order…", "primary"); p.onclick = openPublish;
    b.append(x, p); bars.append(b);
  }
  // Rows, timed from the set on air.
  const rows = $("rows"); rows.replaceChildren();
  const airAt = air ? order.indexOf(air.id) : -1;
  const anchor = air ? d.now_ns - air.off * SEC : d.now_ns;
  const startOf = k => { let t = anchor; if (airAt < 0) { for (let j = 0; j < k; j++) t += secondsOf(order[j]) * SEC; return t; }
    if (k >= airAt) { for (let j = airAt; j < k; j++) t += secondsOf(order[j]) * SEC; } else { for (let j = k; j < airAt; j++) t -= secondsOf(order[j]) * SEC; } return t; };
  order.forEach((id, k) => {
    const s = sets[id]; if (!s) return;
    const tr = el("tr"); if (moved.has(id)) tr.className = "moved";
    const fixed = air && air.id === id;
    const h = el("td", "⠿", "handle" + (fixed ? " fixed" : "")); tr.draggable = !fixed;
    tr.ondragstart = e => { dragging = true; e.dataTransfer.setData("text/plain", id); };
    tr.ondragend = () => { dragging = false; };
    tr.ondragover = e => { e.preventDefault(); tr.classList.add("over"); }; tr.ondragleave = () => tr.classList.remove("over");
    tr.ondrop = e => { e.preventDefault(); tr.classList.remove("over"); dragging = false; const from = e.dataTransfer.getData("text/plain"); if (from && from !== id) move(from, order.filter(x => x !== from).indexOf(id)); };
    const t = el("td"); t.append(el("div", s.title, "set-title"));
    if (moved.has(id)) t.append(el("div", base.includes(id) ? (order.indexOf(id) < base.indexOf(id) ? "MOVED ↑" : "MOVED ↓") : "NEW", "tag-moved"));
    const th = el("td"); th.append(thumb(s));
    const t0 = startOf(k), t1 = t0 + secondsOf(id) * SEC;
    const st = air && k < airAt ? el("td", "played", "st-played") : fixed ? el("td", "● on air", "st-air") : el("td", "ready", "muted");
    const dots = el("button", "⋯", "dots"); dots.onclick = e => { e.stopPropagation(); openMenu(dots, id, true); };
    const md = el("td"); md.append(dots);
    const cc = el("td", null, "colcell"); const own = colors[id];
    const mark = el("span", null, own ? "cdot" : "cring"); if (own) mark.style.background = own;
    cc.append(mark, document.createTextNode(own ? colourName(own) : "Station")); cc.onclick = e => { e.stopPropagation(); setColourPop(cc, id, k); };
    tr.append(h, el("td", String(k + 1), "mono muted"), th, t, el("td", s.artist || "—"), el("td", hms(s.seconds), "mono"), el("td", hm(t0) + "–" + hm(t1), "mono"), cc, st, md);
    rows.append(tr);
  });
  const out = $("out"); out.replaceChildren();
  const left = d.sets.filter(s => s.status === "ready" && !order.includes(s.id) && s.title !== "Test reel");
  if (left.length) {
    out.append(el("div", "Not in the order", "label"));
    for (const s of left) { const r = el("div", null, "row"); r.style.cssText = "display:flex;gap:12px;align-items:center;padding:8px 0";
      r.append(thumb(s), el("span", s.title, "set-title"), el("span", s.artist || "", "muted"));
      const add = el("button", "Add to the order"); add.onclick = () => saveDraft(order.concat([s.id])); r.append(add); out.append(r); }
  }
}
function openPublish() {
  const sets = byId(), base = baseOrder(), moved = movedIds(base, order), dlg = $("pub"), D = d.D_ms * 1e6;
  const from = d.queued || d.live, nextN = Math.max(0, ...[d.live, d.queued].filter(Boolean).map(e => e.n)) + 1;
  dlg.replaceChildren();
  dlg.append(el("h2", "Publish the new running order"));
  const cols = el("div", null, "cols");
  const list = (title, ids, mark) => { const c = el("div"); c.append(el("div", title, "label")); const ol = el("ol");
    ids.forEach((id, k) => { const li = el("li"); if (mark.has(id)) li.className = "hl"; li.append(el("b", String(k + 1)), document.createTextNode(sets[id] ? sets[id].title : "?")); ol.append(li); }); c.append(ol); return c; };
  cols.append(list("Now · edition " + (from ? from.n : "—"), base, moved), el("div", "→", "muted"), list("New · edition " + nextN, order, moved));
  dlg.append(cols, el("div", "When it takes effect", "label"));
  const at = d.plan.start_ns + D, earliest = d.plan.earliest_ns + D;
  const replacing = d.queued && !d.locked;
  let when = "boundary";
  const o1 = el("label", null, "opt on"), o2 = el("label", null, "opt");
  const r1 = el("input"); r1.type = "radio"; r1.name = "when"; r1.checked = true;
  const r2 = el("input"); r2.type = "radio"; r2.name = "when";
  const t1 = el("div"); t1.append(el("div", replacing ? "At " + hm(at) + ", as queued" : "When " + (d.plan.ending || "the set on air") + " ends · " + hm(at)),
    el("div", replacing ? "Keeps the queued order's time; it's still at least 10 min away." : "The next set boundary at least 10 min away. Nothing is cut off.", "muted small"));
  const time = el("input"); time.type = "time"; time.style.width = "130px"; time.style.marginTop = "6px";
  const e0 = new Date(earliest / 1e6); time.value = String(e0.getHours()).padStart(2, "0") + ":" + String(e0.getMinutes() + (e0.getSeconds() ? 1 : 0)).padStart(2, "0");
  const t2 = el("div"); t2.append(el("div", "At a set time"), el("div", "Cuts whatever is playing at that moment. Earliest " + hm(earliest + 59e9) + " (10 min from now, so every phone can fetch it).", "muted small"), time);
  o1.append(r1, t1); o2.append(r2, t2); dlg.append(o1, o2);
  const msg = el("textarea"); msg.rows = 3; msg.maxLength = d.message_max; msg.placeholder = "Optional: shown on every phone until the change";
  const count = el("span", "0 / " + d.message_max, "mono small muted");
  const go = el("button", null, "primary");
  function setTime() { // the set time, today or tomorrow, as heard
    const [h, m] = time.value.split(":").map(Number); const t = new Date(); t.setHours(h, m, 0, 0); if (t.getTime() * 1e6 < earliest) t.setDate(t.getDate() + 1); return t.getTime() * 1e6; }
  const update = () => { when = r2.checked ? "time" : "boundary"; o1.className = "opt" + (r1.checked ? " on" : ""); o2.className = "opt" + (r2.checked ? " on" : "");
    go.textContent = "Publish · takes effect " + hm(when === "time" ? setTime() : at); count.textContent = msg.value.length + " / " + d.message_max; };
  r1.onchange = r2.onchange = time.oninput = msg.oninput = update; time.onfocus = () => { r2.checked = true; update(); };
  dlg.append(el("div", "Message to listeners · optional", "label"), msg);
  const foot = el("div", null, "row"); foot.style.cssText = "display:flex;justify-content:space-between"; foot.append(el("span", "Shown on every phone from now until the change, then in the event log.", "muted small"), count); dlg.append(foot);
  const btns = el("div"); btns.style.cssText = "display:flex;justify-content:flex-end;gap:10px;margin-top:16px";
  const cancel = el("button", "Cancel"); cancel.onclick = () => dlg.close();
  go.onclick = async () => {
    const q = new URLSearchParams({order: order.join(","), when, message: msg.value, colors: colorParam(colors)}); if (when === "time") q.set("at", String(setTime()));
    go.disabled = true; const ok = await act("/api/publish?" + q, "POST"); go.disabled = false; if (ok) dlg.close();
  };
  btns.append(cancel, go); dlg.append(btns); update(); dlg.showModal();
}
// Upload: one file at a time, three steps: uploaded, converting, signing.
let pick = null, sending = null;
$("drop").onclick = () => $("file").click();
$("drop").ondragover = e => { e.preventDefault(); $("drop").classList.add("hot"); }; $("drop").ondragleave = () => $("drop").classList.remove("hot");
$("drop").ondrop = e => { e.preventDefault(); $("drop").classList.remove("hot"); if (e.dataTransfer.files[0]) choose(e.dataTransfer.files[0]); };
$("file").onchange = () => { if ($("file").files[0]) choose($("file").files[0]); };
function choose(f) {
  if (f.size > 4 * 1073741824) { alert("Up to 4 GB."); return; }
  if (d && d.free - f.size * 2 < d.reserve) { alert("Not enough disk: this would leave less than " + gb(d.reserve) + " free."); return; }
  pick = f; $("picked").hidden = false; $("fname").textContent = f.name; $("fsize").textContent = gb(f.size); diskBar();
}
$("unpick").onclick = () => { pick = null; $("picked").hidden = true; $("file").value = ""; diskBar(); };
$("go").onclick = () => {
  if (!pick) return; const f = pick;
  const q = new URLSearchParams({name: f.name, title: $("title").value, artist: $("artist").value, set: $("set").value, goes: $("goes").value});
  const x = new XMLHttpRequest(); x.open("PUT", "/api/upload?" + q); x.setRequestHeader("X-Brocast", "1");
  sending = {name: f.name, size: f.size, frac: 0}; $("go").disabled = true;
  x.upload.onprogress = p => { sending.frac = p.loaded / p.total; jobs(); };
  x.onloadend = () => { $("go").disabled = false; let j = {}; try { j = JSON.parse(x.responseText); } catch (_) {}
    if (x.status !== 200) alert(j.error || "The upload didn't finish. Please try again.");
    sending = null; $("unpick").onclick(); ["title", "artist", "set"].forEach(k => $(k).value = ""); refresh(true); };
  x.send(f); jobs();
};
function job(name, size, a, b, c, labels, err) {
  const box = el("div"); box.style.marginTop = "8px";
  const r = el("div", null, "row"); r.append(el("span", name), el("span", size, "mono small")); box.append(r);
  const steps = el("div", null, "steps"); for (const v of [a, b, c]) { const s = el("div"), i = el("i"); i.style.width = Math.round(v * 100) + "%"; s.append(i); steps.append(s); } box.append(steps);
  const l = el("div", null, "steps small"); labels.forEach((t, k) => l.append(el("span", t, [a, b, c][k] > 0 ? "" : "muted"))); l.style.marginTop = "4px"; box.append(l);
  if (err) box.append(el("div", err, "bad small"));
  return box;
}
function jobs() {
  const box = $("jobs"); box.replaceChildren();
  if (sending) box.append(job(sending.name, gb(sending.size), sending.frac, 0, 0, ["Uploading · " + Math.round(sending.frac * 100) + "%", "Converting", "Signing"]));
  if (!d) return;
  for (const s of d.sets.filter(s => s.status !== "ready")) {
    if (s.status === "failed") { const j = job(s.title || s.filename, "", 1, 0, 0, ["Uploaded", "Converting", "Signing"], "Failed: " + s.error);
      const x = el("button", "Remove"); x.onclick = () => act("/api/sets/" + s.id, "DELETE"); j.append(x); box.append(j); }
    else { const p = s.progress || 0; box.append(job(s.title || s.filename, "", 1, s.status === "queued" ? 0 : Math.max(.02, p), p >= 1 ? .5 : 0,
      ["Uploaded", s.status === "queued" ? "Waiting" : "Converting · " + Math.round(p * 100) + "%", "Signing"])); }
  }
}
function diskBar() {
  if (!d) return; const total = d.used + d.free, need = pick ? pick.size * 2 : 0;
  $("disk-free").textContent = gb(d.free) + " free · library " + gb(d.used) + " of " + gb(d.max);
  const bar = $("disk-bar"); bar.replaceChildren();
  const seg = (v, bg) => { const i = el("i"); i.style.width = (100 * v / total) + "%"; i.style.background = bg; bar.append(i); };
  seg(d.used, "var(--text2)"); if (need) seg(need, "var(--accent)"); seg(Math.max(0, d.free - need - d.reserve), "transparent");
  seg(d.reserve, "repeating-linear-gradient(135deg, var(--danger) 0 2px, transparent 2px 5px)");
  $("disk-key").textContent = "■ sets " + gb(d.used) + (need ? " · ■ this upload needs about " + gb(need) : "") + " · ▨ " + gb(d.reserve) + " always kept free";
}
let artistsShown = "";
function showArtists() {
  const artists = d.artists || [], sig = JSON.stringify(artists.map(a => [a.id, a.v, a.name, a.aliases, a.focus]));
  $("artist-names").replaceChildren(...artists.map(a => { const o = el("option"); o.value = a.name; return o; }));
  const faces = $("faces"); faces.replaceChildren();
  for (const a of artists.slice(0, 4)) { const i = el("img"); i.src = "/api/artists/" + a.id + "/1x1.jpg?v=" + a.v; i.alt = a.name; i.title = a.name; faces.append(i); }
  if (artists.length > 4) faces.append(el("span", "+" + (artists.length - 4), "muted"));
  if (!artists.length) faces.append(el("span", "No artists yet", "muted small"));
  const conf = $("confirms"); conf.replaceChildren();
  for (const s of d.sets.filter(s => s.status === "ready" && !s.artist_sure && s.title !== "Test reel")) {
    const c = el("div", null, "confirm"), names = (s.artist_ids || []).map(i => (artists.find(a => a.id === i) || {}).name).filter(Boolean);
    const t = el("span"); t.style.flex = "1";
    t.append(document.createTextNode("“" + (s.artist || s.title) + "” on " + s.title + ": "));
    if (names.length) { t.append(document.createTextNode("same as ")); t.append(el("b", names.join(", "))); t.append(document.createTextNode("?")); } else t.append(document.createTextNode("no artist matches."));
    if (names.length) { const y = el("a", "Yes"); y.onclick = () => act("/api/sets/" + s.id + "/artists?ids=" + s.artist_ids.join(","), "POST"); c.append(t, y); } else c.append(t);
    const n = el("a", "New artist"); n.onclick = () => { setView("artists"); $("aname").value = s.artist || ""; $("aname").focus(); }; c.append(n); conf.append(c);
  }
  if (sig === artistsShown) return; artistsShown = sig;   // rebuilding every 2 s would reload the photos
  const box = $("artists"); box.replaceChildren();
  for (const a of artists) {
    const row = el("div", null, "artist");
    const ph = el("div", null, "photo"); const img = el("img"); img.src = "/api/artists/" + a.id + "/photo.jpg?v=" + a.v; ph.append(img);
    const dot = el("div", null, "dot"); dot.style.left = (a.focus[0] * 100) + "%"; dot.style.top = (a.focus[1] * 100) + "%"; ph.append(dot);
    ph.onclick = e => { const r = img.getBoundingClientRect(); act("/api/artists/" + a.id + "/focus?x=" + ((e.clientX - r.left) / r.width).toFixed(3) + "&y=" + ((e.clientY - r.top) / r.height).toFixed(3), "POST"); };
    const info = el("div"); info.append(el("div", a.name, "set-title"));
    if (a.aliases && a.aliases.length) info.append(el("p", "Also: " + a.aliases.join("; "), "muted small"));
    const crops = el("div", null, "crops"); for (const c of ["1x1", "9x16", "16x9"]) { const i = el("img"); i.src = "/api/artists/" + a.id + "/" + c + ".jpg?v=" + a.v; i.alt = c; crops.append(i); } info.append(crops);
    const ren = el("button", "Rename"); ren.onclick = () => { const n = prompt("Name", a.name); if (n === null) return;
      const al = prompt("Other spellings, separated by ;", (a.aliases || []).join("; ")); act("/api/artists/" + a.id + "?" + new URLSearchParams({name: n, aliases: al || ""}), "POST"); };
    const del = el("button", "Delete"); del.onclick = () => { if (confirm("Delete " + a.name + "?")) act("/api/artists/" + a.id, "DELETE"); };
    info.append(ren, del); row.append(ph, info); box.append(row);
  }
}
$("aform").onsubmit = e => {
  e.preventDefault(); const file = $("afile").files[0]; if (!file) return;
  const q = new URLSearchParams({name: $("aname").value, aliases: $("aalias").value});
  $("ago").disabled = true; $("amessage").textContent = "Uploading…";
  fetch("/api/artists?" + q, {method: "PUT", headers, body: file}).then(async r => { const j = await r.json().catch(() => ({})); $("ago").disabled = false;
    $("amessage").textContent = r.ok ? "Added. Click the photo where the face is." : (j.error || "That didn't work"); if (r.ok) $("aform").reset(); refresh(true); });
};
function setView(v) { view = v; $("view-order").hidden = v !== "order"; $("view-artists").hidden = v !== "artists";
  $("nav-order").className = v === "order" ? "on" : ""; $("nav-artists").className = v === "artists" ? "on" : ""; }
$("nav-order").onclick = () => setView("order"); $("nav-artists").onclick = () => setView("artists"); $("open-artists").onclick = () => setView("artists");
$("nav-upload").onclick = () => $("upload-card").scrollIntoView({behavior: "smooth"}); $("nav-disk").onclick = () => $("disk-card").scrollIntoView({behavior: "smooth"}); $("nav-colour").onclick = () => $("colour-card").scrollIntoView({behavior: "smooth"}); $("nav-news").onclick = () => $("news-card").scrollIntoView({behavior: "smooth"});
// Station colour (hand-off 43) and colour per set (hand-off 50).
function swatchGrid(current, pick) {
  const g = el("div", null, "swatches");
  for (const [name, hex] of Object.entries(d.palette)) { const b = el("button", hex === current ? "✓" : "", "sw" + (hex === current ? " cur" : "")); b.style.background = hex; b.title = name; b.onclick = e => { e.stopPropagation(); pick(hex); }; g.append(b); }
  return g;
}
function showColour() {
  const c = d.colour, name = colourName(c.hex);
  document.documentElement.style.setProperty("--accent", c.hex);
  $("nav-colour-sub").replaceChildren(Object.assign(el("span", null, "cdot"), {style: "background:" + c.hex}), document.createTextNode(name));
  $("colour-now").replaceChildren(Object.assign(el("span", null, "cdot"), {style: "background:" + c.hex}), document.createTextNode(name + (c.hex === d.palette.Lime ? " · default" : "") + (c.at_ns > d.now_ns ? " · from " + hm(c.at_ns) : "")));
  $("swatches").replaceWith(Object.assign(swatchGrid(c.hex, hex => { if (hex !== c.hex) openColour(hex); }), {id: "swatches"}));
}
function openColour(hex) {
  const dlg = $("colour-dlg"), name = colourName(hex), cur = d.colour.hex;
  dlg.replaceChildren(el("h2", "Change the station colour to " + name + "?"));
  const pv = el("div", null, "preview");
  for (const [label, h] of [["Now · " + colourName(cur), cur], ["New · " + name, hex]]) { const b = el("div"); b.append(el("div", label, "label")); const pill = el("div", "● IN SYNC ±3.0 ms", "pill"); pill.style.background = h; b.append(pill); pv.append(b); }
  dlg.append(pv, el("div", "When", "label"));
  const mk = (title, sub, on) => { const o = el("label", null, "opt" + (on ? " on" : "")); const r = el("input"); r.type = "radio"; r.name = "cwhen"; r.checked = on; const t = el("div"); t.append(el("div", title), el("div", sub, "muted small")); o.append(r, t); return [o, r]; };
  const [o1, r1] = mk("Now", "Fades over 2 s on every phone; phones that are offline get it when they reconnect.", true);
  const [o2, r2] = mk("At the next set", "Changes on the next set boundary. Sets with their own colour (Colour column in the running order) keep it.", false);
  r1.onchange = r2.onchange = () => { o1.className = "opt" + (r1.checked ? " on" : ""); o2.className = "opt" + (r2.checked ? " on" : ""); };
  const tell = el("label", null, "opt on"); const tc = el("input"); tc.type = "checkbox"; tc.checked = true; const tt = el("div"); tt.append(el("div", "Tell listeners"), el("div", "“Beach Sync Radio is " + name + " now” for 4 s", "muted small")); tell.append(tc, tt);
  dlg.append(o1, o2, tell, el("p", "Contrast with the dark button text: at least 7 : 1, like all 21 colours.", "muted small"));
  const btns = el("div"); btns.style.cssText = "display:flex;justify-content:flex-end;gap:10px;margin-top:16px";
  const cancel = el("button", "Cancel"); cancel.onclick = () => dlg.close();
  const go = el("button", "Change to " + name, "primary"); go.style.background = hex; go.style.borderColor = hex;
  go.onclick = async () => { go.disabled = true; const ok = await act("/api/colour?" + new URLSearchParams({hex: hex.slice(1), when: r2.checked ? "next" : "now", tell: tc.checked ? "1" : "0"}), "POST"); go.disabled = false; if (ok) dlg.close(); };
  btns.append(cancel, go); dlg.append(btns); dlg.showModal();
}
function setColourPop(cell, id, k) {
  closeMenu(); const sets = byId(), title = sets[id] ? sets[id].title : "?";
  menu = el("div", null, "colpop"); const r = cell.getBoundingClientRect(); menu.style.left = (r.left + scrollX) + "px"; menu.style.top = (r.bottom + scrollY + 4) + "px";
  menu.onclick = e => e.stopPropagation();
  menu.append(el("div", "Colour for " + title, "label"));
  const station = el("label", null, "opt" + (colors[id] ? "" : " on")); const sr = el("input"); sr.type = "radio"; sr.checked = !colors[id];
  station.append(sr, el("div", "Use the station colour (" + colourName(d.colour.hex) + ")")); sr.onchange = () => { delete colors[id]; closeMenu(); saveDraft(order); };
  menu.append(station, swatchGrid(colors[id], hex => { colors[id] = hex; closeMenu(); saveDraft(order); }));
  const prev = order[(k + order.length - 1) % order.length], pc = colors[prev] || d.colour.hex, nc = colors[id] || d.colour.hex;
  const line = el("div", null, "muted small"); line.append(document.createTextNode((sets[prev] ? sets[prev].title : "") + " "), Object.assign(el("span", null, "cdot"), {style: "background:" + pc}), document.createTextNode("→ "), Object.assign(el("span", null, "cdot"), {style: "background:" + nc}), document.createTextNode("every phone changes on beat 1 of the first bar"));
  menu.append(line); document.body.append(menu);
}
// Station news bulletin (hand-off 49): BBC World Service (default), NPR News Now or None.
const NEWS = [["bbc", "BBC World Service", "Hourly · about 5 min · default"], ["npr", "NPR News Now", "Hourly · about 5 min"], ["none", "None", "No news on the headphone button for this station"]];
function showNews() {
  const n = d.news; $("nav-news-sub").textContent = n.name;
  $("news-now").textContent = n.choice === "none" ? "None" : n.name + (n.url ? " · live" : " · no feed URL set");
  $("news-opts").replaceChildren(...NEWS.map(([k, name, sub]) => {
    const o = el("label", null, "opt news" + (n.choice === k ? " on" : "")); const r = el("input"); r.type = "radio"; r.name = "news"; r.checked = n.choice === k;
    r.onchange = async () => { await fetch("/api/news?choice=" + k, {method: "POST", headers}); refresh(true); };
    const t = el("div"); t.append(el("b", name), el("div", sub, "muted small"));
    if (n.choice === k && k !== "none") t.append(el("div", n.url ? n.url.replace("https://", "") : "Feed URL not set: add it to station.json on the server", "mono small faint"));
    o.append(r, t); return o;
  }));
}
async function refresh(force) {
  if (!force && (dragging || menu || $("pub").open)) return;   // never redraw under the operator's hand
  d = await (await fetch("/api/sets")).json();
  order = d.draft || baseOrder();
  const live = d.live;
  $("live").replaceChildren(); if (live) { $("live").append("Live: "); $("live").append(el("b", "edition " + live.n)); $("live").append(" since " + hm(live.start_ns + d.D_ms * 1e6) + " · loops every " + hms(live.items.reduce((a, i) => a + i.seconds, 0))); }
  else $("live").textContent = "Nothing on air yet: the first finished upload starts the station.";
  $("nav-order-sub").textContent = order.length + " sets";
  $("nav-upload-sub").textContent = (d.sets.filter(s => s.status === "queued" || s.status === "transcoding").length + (sending ? 1 : 0)) + " in progress";
  const unsure = d.sets.filter(s => s.status === "ready" && !s.artist_sure && s.title !== "Test reel").length;
  $("nav-artists-sub").textContent = (d.artists || []).length + (unsure ? " · " + unsure + " to confirm" : "");
  $("nav-disk-sub").textContent = gb(d.free) + " free";
  colors = d.draft_colors || baseColors();
  render(); jobs(); diskBar(); showArtists(); showNews(); showColour();
}
refresh(true); setInterval(refresh, 2000);
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
                r = lib.running()
                self.reply(sets=lib.sets(), on_air=lib.on_air(), free=lib.free(), used=lib.used(), max=lib.max, reserve=lib.reserve, artists=lib.artists(),
                           live=r["live"], queued=r["queued"], locked=r["locked"], draft=lib.draft(), plan=lib.plan(),
                           D_ms=LIBRARY_D_MS, notice_s=LIBRARY_NOTICE_S, now_ns=time.time_ns(), message_max=MESSAGE_MAX,
                           news={**(n := lib.news()), "url": n["url"].split("?")[0]}, colour=lib.colour(), palette=PALETTE, draft_colors=lib.draft_colors())   # the key never reaches the page
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
            set_id = lib.add(temp, field("name") or "upload", field("title"), field("artist"), field("set"), field("goes"))
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
                if url.path == "/api/draft":   # the page's working order, kept on the server so it survives a reload
                    ids = [i for i in (q.get("order") or [""])[0].split(",") if ID.match(i)]
                    lib.save_draft(ids, parse_colors(q) if "colors" in q else None)
                    return self.reply()
                if url.path == "/api/publish":
                    at = field("at")
                    msg = (q.get("message") or [""])[0][:MESSAGE_MAX + 1]
                    e = lib.publish([i for i in (q.get("order") or [""])[0].split(",") if ID.match(i)], field("when"), int(at) if at.isdigit() else None, msg, parse_colors(q))
                    return self.reply(n=e["n"], start_ns=e["start_ns"])
                if url.path == "/api/colour":
                    lib.set_colour("#" + field("hex").lstrip("#").upper(), field("when"), field("tell") == "1")
                    return self.reply()
                if url.path == "/api/news":
                    lib.set_news(field("choice"))
                    return self.reply()
                if url.path == "/api/queued/message":
                    lib.edit_message((q.get("message") or [""])[0][:MESSAGE_MAX + 1])
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
            if path == "/api/draft":
                lib.save_draft(None)
                self.reply()
                return
            if path == "/api/queued":
                try:
                    lib.cancel_queued()
                    self.reply()
                except ValueError as e:
                    self.reply(400, error=str(e))
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
