#!/usr/bin/env python3
"""beachsync_server: the prototype's stream server.

Serves a live, time-stamped PCM stream in 1-second segments, exactly as the design says:
segment n holds samples n*48000 .. n*48000+47999 and its stamp is start_utc + n seconds,
counted from the sample number, never re-read from the clock.

    python3 beachsync_server.py --clicks                 # built-in click track (one click per second)
    python3 beachsync_server.py --clicks-split           # the same, but 1000 Hz on the left, 2500 Hz on the right
    python3 beachsync_server.py --source mysong.flac     # your own music file (needs ffmpeg), looped

Then phones fetch   http://<this machine>:8080/manifest.json   and   /seg/<n>.pcm

Needs Python 3.8+. ffmpeg only for --source. Keep this machine's clock on NTP (chrony): any
error in it shifts every phone by the same amount, so it does not affect sync between phones.
Use music you own or that is licensed for free use.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import struct
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RATE = 48000
CHANNELS = 2
BYTES_PER_FRAME = 2 * CHANNELS  # s16le stereo
SEG = RATE  # samples per segment = 1 s
RETAIN = 120  # segments kept available behind the live edge


def click_track(seconds=4, freq=2000.0, freq_r=None):
    """One sharp 5 ms tone burst at the start of every second; the rest silence.
    With freq_r, the right channel's burst is at freq_r instead, for the acoustic stream test."""
    freq_r = freq if freq_r is None else freq_r
    total = RATE * seconds
    frames = bytearray(total * BYTES_PER_FRAME)
    burst = int(0.005 * RATE)
    ramp = int(0.0002 * RATE)  # 0.2 ms attack so the onset is sharp but not a pure step
    for s in range(seconds):
        base = s * RATE
        for i in range(burst):
            env = min(1.0, (i + 1) / ramp) * (0.5 + 0.5 * math.cos(math.pi * i / burst))
            v = int(0.8 * 32767 * env * math.sin(2 * math.pi * freq * i / RATE))
            w = int(0.8 * 32767 * env * math.sin(2 * math.pi * freq_r * i / RATE))
            struct.pack_into("<hh", frames, (base + i) * BYTES_PER_FRAME, v, w)
    return bytes(frames)


def decode_file(path):
    cmd = ["ffmpeg", "-v", "error", "-i", path, "-f", "s16le", "-acodec", "pcm_s16le",
           "-ac", str(CHANNELS), "-ar", str(RATE), "-"]
    try:
        pcm = subprocess.run(cmd, check=True, capture_output=True).stdout
    except FileNotFoundError:
        sys.exit("ffmpeg not found: sudo apt install ffmpeg")
    except subprocess.CalledProcessError as e:
        sys.exit(f"ffmpeg could not decode {path}: {e.stderr.decode(errors='replace')[:400]}")
    if len(pcm) < BYTES_PER_FRAME * RATE:
        sys.exit("source is shorter than one second")
    return pcm


class MemoryAudio:
    """Audio held in memory: a click track or a decoded --source file."""

    def __init__(self, pcm: bytes):
        self.pcm = pcm
        self.frames = len(pcm) // BYTES_PER_FRAME

    def read(self, frame: int, count: int) -> bytes:
        return self.pcm[frame * BYTES_PER_FRAME:(frame + count) * BYTES_PER_FRAME]


class FileAudio:
    """Audio read from disk as needed, so a long set doesn't sit in memory. Closed once nothing is using it."""

    def __init__(self, path: str):
        self.fd = os.open(path, os.O_RDONLY)
        self.frames = os.fstat(self.fd).st_size // BYTES_PER_FRAME

    def read(self, frame: int, count: int) -> bytes:
        return os.pread(self.fd, count * BYTES_PER_FRAME, frame * BYTES_PER_FRAME)

    def __del__(self):
        os.close(self.fd)


class Stream:
    def __init__(self, audio, d_ms: int, label: str, start_ns: int | None = None):
        self.audio = audio
        self.frames = audio.frames
        self.d_ms = d_ms
        self.label = label
        now = time.time_ns()
        self.start_ns = (now // 1_000_000_000 + 1) * 1_000_000_000 if start_ns is None else start_ns  # next whole second

    def newest(self):
        """Newest COMPLETE segment: segment n is complete once its last sample has been 'captured'."""
        elapsed = time.time_ns() - self.start_ns
        return elapsed // 1_000_000_000 - 1

    def segment(self, n: int) -> bytes:
        first = (n * SEG) % self.frames
        out = bytearray()
        need = SEG
        pos = first
        while need:
            take = min(need, self.frames - pos)
            out += self.audio.read(pos, take)
            need -= take
            pos = 0
        return bytes(out)

    def manifest(self):
        return {"version": 1, "sample_rate": RATE, "channels": CHANNELS, "format": "s16le",
                "segment_samples": SEG, "start_utc_ns": self.start_ns, "newest": self.newest(),
                "oldest": max(0, self.newest() - RETAIN), "D_ms": self.d_ms,
                "server_utc_ns": time.time_ns(), "source": self.label}


class LibraryStream:
    """Plays what the upload page (brocast_admin.py) has put on air, or the fallback when nothing is.
    This side only ever reads the library. Phones notice a new start time and begin the new stream on their own."""

    def __init__(self, directory: str, d_ms: int, fallback: Stream):
        self.directory = directory
        self.d_ms = d_ms
        self.fallback = fallback
        self.current = fallback
        self.seen = None
        self.lock = threading.Lock()

    def stream(self) -> Stream:
        path = os.path.join(self.directory, "on_air.json")
        try:
            stamp = os.stat(path).st_mtime_ns
        except OSError:
            stamp = None
        with self.lock:
            if stamp != self.seen:
                self.seen = stamp
                self.current = self.load(path) if stamp is not None else self.fallback
            return self.current

    def load(self, path: str) -> Stream:
        try:
            with open(path) as f:
                air = json.load(f)
            audio = FileAudio(os.path.join(self.directory, "sets", air["id"], "audio.pcm"))
            if audio.frames < SEG:
                raise ValueError("too short")
            return Stream(audio, self.d_ms, air["label"], air["start_ns"])
        except (OSError, ValueError, KeyError) as e:
            print(f"couldn't load what's on air, using the fallback: {e}", file=sys.stderr)
            return self.fallback

    def newest(self):
        return self.stream().newest()

    def segment(self, n: int) -> bytes:
        return self.stream().segment(n)

    def manifest(self):
        return self.stream().manifest()


def make_handler(stream):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def send(self, code, body: bytes, ctype: str, cache: str):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", cache)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/manifest.json":
                self.send(200, json.dumps(stream.manifest()).encode(), "application/json", "no-store")
                return
            if path.startswith("/seg/") and path.endswith(".pcm"):
                try:
                    n = int(path[5:-4])
                except ValueError:
                    self.send(400, b"bad segment number", "text/plain", "no-store")
                    return
                newest = stream.newest()
                if n > newest or n < max(0, newest - RETAIN):
                    self.send(404, b"segment not available", "text/plain", "no-store")
                    return
                self.send(200, stream.segment(n), "application/octet-stream", "public, max-age=600")
                return
            self.send(404, b"not found", "text/plain", "no-store")

    return Handler


def main():
    p = argparse.ArgumentParser(description="Prototype stream server for the beach sync tests.")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--clicks", action="store_true", help="built-in click track: one click per second")
    src.add_argument("--clicks-split", action="store_true",
                     help="click track at 1000 Hz on the left and 2500 Hz on the right (acoustic stream test)")
    src.add_argument("--source", help="music file to loop (any format ffmpeg reads)")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--bind", default="0.0.0.0", help="address to listen on (default all)")
    p.add_argument("--d-ms", type=int, default=60000, help="playout delay D advertised to phones (ms)")
    p.add_argument("--library", help="folder from the upload page: play what's on air there, or the source above when nothing is")
    a = p.parse_args()
    if a.clicks:
        pcm, label = click_track(), "clicks"
    elif a.clicks_split:
        pcm, label = click_track(freq=1000.0, freq_r=2500.0), "clicks-split"
    else:
        pcm, label = decode_file(a.source), a.source
    stream = Stream(MemoryAudio(pcm), a.d_ms, label)
    if a.library:
        stream = LibraryStream(a.library, a.d_ms, stream)
    srv =ThreadingHTTPServer((a.bind, a.port), make_handler(stream))
    print(f"D = {a.d_ms} ms, serving on {a.bind}:{a.port}  (Ctrl-C to stop)")
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
