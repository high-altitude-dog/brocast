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
import tempfile
import threading
from array import array
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RATE = 48000
CHANNELS = 2
BYTES_PER_FRAME = 2 * CHANNELS  # s16le stereo
SEG = RATE  # samples per segment = 1 s
RETAIN = 120  # segments kept available behind the live edge
PACKETS_PER_BLOCK = 50  # 20 ms Opus packets in each 1 s block
BLOCK_FORMAT = "opus-20ms-1s"
OPUS_SILENCE = bytes([0xF8, 0xFF, 0xFE])  # one 20 ms stereo packet of silence


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


class OpusBlocks:
    """A 20 ms Ogg Opus file as 1 s blocks, read from disk as needed. Only an index sits in memory.
    A block is each packet in turn as a little-endian 2-byte length and its bytes."""

    def __init__(self, path: str):
        self.fd = os.open(path, os.O_RDONLY)
        self.first = array("I", [0])    # for each packet, its first piece: a packet can run across Ogg pages
        self.where = array("Q")
        self.size = array("I")
        open_packet = False
        at = 0
        while True:
            head = os.pread(self.fd, 27, at)
            if len(head) < 27:
                break
            if head[:4] != b"OggS":
                raise ValueError("not an Ogg file")
            count = head[26]
            laces = os.pread(self.fd, count, at + 27)
            data = at + 27 + count
            piece = 0
            for lace in laces:
                piece += lace
                if lace < 255:
                    self.add(data, piece, True)
                    data += piece
                    piece = 0
                    open_packet = False
            if piece:
                self.add(data, piece, False)
                data += piece
                open_packet = True
            at = data
        if open_packet or len(self.first) < 3:
            raise ValueError("no complete Opus stream")
        head = self.packet(0)
        if head[:8] != b"OpusHead" or self.packet(1)[:8] != b"OpusTags":
            raise ValueError("not an Opus stream")
        self.packets = len(self.first) - 3

    def add(self, where: int, size: int, ends: bool):
        self.where.append(where)
        self.size.append(size)
        if ends:
            self.first.append(len(self.where))

    def packet(self, i: int) -> bytes:
        return b"".join(os.pread(self.fd, self.size[k], self.where[k]) for k in range(self.first[i], self.first[i + 1]))

    def block(self, n: int) -> bytes:
        out = bytearray()
        for i in range(n * PACKETS_PER_BLOCK, (n + 1) * PACKETS_PER_BLOCK):
            p = self.packet(i + 2) if i < self.packets else OPUS_SILENCE
            out += struct.pack("<H", len(p)) + p
        return bytes(out)

    def __del__(self):
        if hasattr(self, "fd"):
            os.close(self.fd)


def encode_opus(pcm: bytes):
    """Blocks for audio held in memory, with the same settings as the upload page. None without ffmpeg: phones then get raw audio."""
    folder = tempfile.mkdtemp(prefix="beachsync-opus-")
    path = os.path.join(folder, "set.opus")
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "s16le", "-ar", str(RATE), "-ac", str(CHANNELS), "-i", "-",
           "-c:a", "libopus", "-b:a", "160k", "-vbr", "on", "-frame_duration", "20", "-page_duration", "1000000", "-f", "ogg", path]
    try:
        subprocess.run(cmd, input=pcm, check=True, capture_output=True)
        blocks = OpusBlocks(path)
    except (OSError, subprocess.CalledProcessError, ValueError) as e:
        print(f"no Opus for this source, so phones get raw audio: {e}", file=sys.stderr)
        return None
    os.unlink(path)   # the open file stays readable, and goes when the server stops
    os.rmdir(folder)
    return blocks


class Stream:
    def __init__(self, audio, d_ms: int, label: str, start_ns: int | None = None, opus: OpusBlocks | None = None):
        self.audio = audio
        self.opus = opus
        # Rounded up to whole seconds, with silence, so that a loop is a whole number of blocks and both formats match.
        self.frames = (audio.frames + SEG - 1) // SEG * SEG
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
            piece = self.audio.read(pos, take) if pos < self.audio.frames else b""
            out += piece + bytes(take * BYTES_PER_FRAME - len(piece))
            need -= take
            pos = 0
        return bytes(out)

    def block(self, n: int) -> bytes | None:
        return self.opus.block(n % (self.frames // SEG)) if self.opus else None

    def manifest(self):
        extra = {"block_format": BLOCK_FORMAT} if self.opus else {}
        return {**extra, "version": 1, "sample_rate": RATE, "channels": CHANNELS, "format": "s16le",
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
            folder = os.path.join(self.directory, "sets", air["id"])
            audio = FileAudio(os.path.join(folder, "audio.pcm"))
            if audio.frames < SEG:
                raise ValueError("too short")
            try:
                opus = OpusBlocks(os.path.join(folder, "set.opus"))
            except (OSError, ValueError) as e:
                print(f"no Opus for this set, so phones get raw audio: {e}", file=sys.stderr)
                opus = None
            return Stream(audio, self.d_ms, air["label"], air["start_ns"], opus)
        except (OSError, ValueError, KeyError) as e:
            print(f"couldn't load what's on air, using the fallback: {e}", file=sys.stderr)
            return self.fallback

    def newest(self):
        return self.stream().newest()

    def segment(self, n: int) -> bytes:
        return self.stream().segment(n)

    def block(self, n: int) -> bytes | None:
        return self.stream().block(n)

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
            if path.startswith("/seg/") and path.endswith((".pcm", ".opus")):
                opus = path.endswith(".opus")
                try:
                    n = int(path[5:path.rindex(".")])
                except ValueError:
                    self.send(400, b"bad segment number", "text/plain", "no-store")
                    return
                newest = stream.newest()
                if n > newest or n < max(0, newest - RETAIN):
                    self.send(404, b"segment not available", "text/plain", "no-store")
                    return
                body = stream.block(n) if opus else stream.segment(n)
                if body is None:
                    self.send(404, b"no Opus for this source", "text/plain", "no-store")
                    return
                self.send(200, body, "application/octet-stream", "public, max-age=600")
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
    stream = Stream(MemoryAudio(pcm), a.d_ms, label, opus=encode_opus(pcm))
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
