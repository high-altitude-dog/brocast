"""Makes app/src/main/assets/land-dots.bin, the dot-matrix land map on the Satellites screen (hand-off 42).

Input: world-atlas land-110m.json (Natural Earth, public domain), for example from
https://cdn.jsdelivr.net/npm/world-atlas@2.0.2/land-110m.json. Stdlib only.

Output: little-endian int16 pairs, latitude and longitude in hundredths of a degree, one per dot on land.
Dots sit on an equal-area grid: rows STEP degrees apart, and along each row STEP / cos(latitude) apart.

    python3 -I land_dots.py <land-110m.json> app/src/main/assets/land-dots.bin
"""
import json
import math
import struct
import sys

STEP = 1.5


def rings(topo):
    sx, sy = topo["transform"]["scale"]
    tx, ty = topo["transform"]["translate"]
    arcs = []
    for arc in topo["arcs"]:
        x = y = 0
        pts = []
        for dx, dy in arc:
            x += dx
            y += dy
            pts.append((x * sx + tx, y * sy + ty))
        arcs.append(pts)

    def ring(ids):
        out = []
        for i in ids:
            a = arcs[i] if i >= 0 else arcs[~i][::-1]
            out.extend(a if not out else a[1:])
        return out

    for g in topo["objects"]["land"]["geometries"]:
        polys = g["arcs"] if g["type"] == "MultiPolygon" else [g["arcs"]]
        for poly in polys:
            yield [ring(r) for r in poly]   # outer ring, then holes


def inside(lon, lat, ring):
    hit = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def main(src, dst):
    polys = []
    for p in rings(json.load(open(src))):
        xs = [x for x, _ in p[0]]
        ys = [y for _, y in p[0]]
        polys.append((min(xs), max(xs), min(ys), max(ys), p))
    out = bytearray()
    n = 0
    lat = -90 + STEP / 2
    while lat < 90:
        step = STEP / max(0.05, math.cos(math.radians(lat)))
        lon = -180 + step / 2
        while lon < 180:
            for x0, x1, y0, y1, p in polys:
                if x0 <= lon <= x1 and y0 <= lat <= y1 and inside(lon, lat, p[0]) and not any(inside(lon, lat, h) for h in p[1:]):
                    out += struct.pack("<hh", round(lat * 100), round(lon * 100))
                    n += 1
                    break
            lon += step
        lat += STEP
    open(dst, "wb").write(out)
    print(n, "dots,", len(out), "bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
