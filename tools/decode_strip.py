#!/usr/bin/env python3
"""Decode the Thor Companion data square from top-screen screenshots.

Usage: decode_strip.py screenshot.png [more.png ...]

Finds the sync row (lightest and darkest shade alternating, COLS cells), takes
the cell size from it, reads the four calibration shades, then classifies every
cell to the nearest of them. A message split into parts is put back together
when all its parts are among the screenshots. See addon/ThorCompanion/Strip.lua
for the layout; the app's decoder is decoder/.../StripDecoder.kt.
"""
import sys
from PIL import Image

COLS = 20
CALIB = 8
SCAN_ROWS = 400
EDGE = 6  # smallest brightness step between neighbouring sync cells that counts


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def lum(p):
    return (p[0] + p[1] + p[2]) / 3


def sync_at(px, w, y):
    """(x of the first cell, cell width) if the sync row crosses row y."""
    l = [lum(px[x, y]) for x in range(w)]
    # Edges: where brightness steps up or down. A step blended over two pixels
    # still gives one peak of the difference across three pixels.
    d = [0.0] + [l[x + 1] - l[x - 1] for x in range(1, w - 1)] + [0.0]
    edges = []
    for x in range(1, w - 1):
        if abs(d[x]) >= EDGE and abs(d[x]) >= abs(d[x - 1]) and abs(d[x]) > abs(d[x + 1]):
            edges.append((x, 1 if d[x] > 0 else -1))
    # The COLS - 1 edges between sync cells: evenly spaced, alternating, first one dark-going.
    need = COLS - 1
    for i in range(len(edges) - need + 1):
        run = edges[i:i + need]
        if run[0][1] != -1 or any(run[k][1] == run[k - 1][1] for k in range(1, need)):
            continue
        pitch = (run[-1][0] - run[0][0]) / (need - 1)
        if pitch < 2.5 or any(abs(run[k][0] - run[0][0] - k * pitch) > max(1.5, pitch / 4) for k in range(need)):
            continue
        x0 = run[0][0] + 0.5 - pitch
        if x0 < 0:
            continue
        # Light cells alike, dark cells alike, and clearly apart.
        light = [l[int(x0 + (c + 0.5) * pitch)] for c in range(0, COLS, 2)]
        dark = [l[int(x0 + (c + 0.5) * pitch)] for c in range(1, COLS, 2)]
        if min(light) - max(dark) >= EDGE and max(light) - min(light) < EDGE and max(dark) - min(dark) < EDGE:
            return x0, pitch
    return None


def find_square(img):
    """(x of first cell, middle y of the sync row, cell size) or None."""
    w, h = img.size
    px = img.load()
    first = first_sync = None
    for y in range(min(h, SCAN_ROWS)):
        found = sync_at(px, w, y)
        if found and first is None:
            first, first_sync = y, found
        elif first is not None and (not found or abs(found[0] - first_sync[0]) > 1.5):
            mid = (first + y - 1) // 2  # edge rows are blended; use the middle
            x0, cell = sync_at(px, w, mid) or first_sync
            return x0, mid, cell
    return None


def decode(img):
    """One part: dict with seq, part, parts, payload bytes and crc_ok, or None."""
    img = img.convert("RGB")
    found = find_square(img)
    if not found:
        return None
    x0, y0, cell = found
    px = img.load()

    def colour(i):
        row, col = divmod(COLS + i, COLS)
        return px[int(x0 + (col + 0.5) * cell), int(round(y0 + row * cell))]

    calib = [colour(c) for c in range(CALIB)]
    shades = [tuple((calib[s][k] + calib[s + 4][k]) / 2 for k in range(3)) for s in range(4)]

    def sym(i):
        c = colour(i)
        return min(range(4), key=lambda s: sum((a - b) ** 2 for a, b in zip(c, shades[s])))

    def byte(n):
        i = CALIB + n * 4
        return (sym(i) << 6) | (sym(i + 1) << 4) | (sym(i + 2) << 2) | sym(i + 3)

    capacity = ((COLS - 1) * COLS - CALIB) // 4
    head = bytes(byte(n) for n in range(4))
    length = head[3]
    if 4 + length + 2 > capacity:
        return {"crc_ok": False, "why": f"bad length {length}"}
    body = bytes(byte(4 + n) for n in range(length))
    crc = (byte(4 + length) << 8) | byte(5 + length)
    spread = min(sum((a - b) ** 2 for a, b in zip(shades[p], shades[q])) ** 0.5
                 for p in range(4) for q in range(p + 1, 4))
    return {"x": round(x0, 1), "row": y0, "cell_px": round(cell, 2), "shade_min_distance": round(spread, 1),
            "version": head[0], "seq": head[1], "part": head[2] >> 4, "parts": (head[2] & 15) + 1,
            "crc_ok": crc == crc16(head + body), "payload": body}


if __name__ == "__main__":
    messages = {}
    for path in sys.argv[1:]:
        res = decode(Image.open(path))
        if not res:
            print(f"{path}: square not found")
            continue
        print(f"{path}: " + ", ".join(f"{k}={v}" for k, v in res.items() if k != "payload"))
        if res["crc_ok"]:
            messages.setdefault((res["seq"], res["parts"]), {})[res["part"]] = res["payload"]
    for (seq, count), got in sorted(messages.items()):
        if len(got) == count:
            print(f"message {seq}: " + b"".join(got[i] for i in range(count)).decode("utf-8", "replace"))
        else:
            print(f"message {seq}: {len(got)} of {count} parts")
