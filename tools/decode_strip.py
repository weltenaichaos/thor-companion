#!/usr/bin/env python3
"""Decode the Thor Companion data block (or an older strip) from a top-screen screenshot.

Usage: decode_strip.py screenshot.png

Finds the sync cells (magenta, green, magenta, green), measures the cell size
(the game is upscaled on the Thor), reads the 64 calibration cells to learn
how each palette colour actually looks on screen, then classifies every data
cell to the nearest calibration colour. See addon/ThorCompanion/Strip.lua for
the layout.
"""
import sys
from PIL import Image

DATA = 72
COLS = 48  # cells per row of the corner block (v3); v2 strips ran full width in 3 rows
MAX_ROWS = 14


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


# Loose classes: the display shifts colours, so test shape, not exact values.
def magenta(p):
    r, g, b = p[:3]
    return r > 150 and b > 150 and g < min(r, b) - 80


def green(p):
    r, g, b = p[:3]
    return g > 150 and r < g - 60 and b < g - 60


def sync_at(px, w, y):
    """(x of first cell, cell width) if the sync cells are on this row, anywhere along it."""
    for x in range(w):
        if magenta(px[x, y]) and (x == 0 or not magenta(px[x - 1, y])):
            found = sync_from(px, w, x, y)
            if found:
                return found
    return None


def sync_from(px, w, x, y):
    starts, want = [x], green
    for xx in range(x, min(w, x + 200)):
        if want(px[xx, y]):
            starts.append(xx)
            want = magenta if want is green else green
            if len(starts) == 4:
                cell = (starts[3] - starts[0]) / 3
                # Real sync cells are evenly spaced; data cells that happen to be
                # magenta and green rarely are.
                slack = max(1.5, cell / 4)
                even = all(abs(starts[i] - starts[i - 1] - cell) <= slack for i in (1, 2, 3))
                return (starts[0], cell) if cell >= 2 and even else None
    return None


def find_strip(img):
    """(x of first cell, middle y of the bottom cell row, cell size) or None."""
    w, h = img.size
    px = img.load()
    first = first_sync = None
    for y in range(h - 1, max(h - 300, -1), -1):
        found = sync_at(px, w, y)
        if found and first is None:
            first, first_sync = y, found
        elif first is not None and (not found or abs(found[0] - first_sync[0]) > 1
                                    or abs(found[1] - first_sync[1]) > 1):
            # The row above the sync cells ends the block, even when its own
            # cells look like a sync at another place or size.
            mid = (first + y + 1) // 2  # edge rows are blended; use the middle
            x0, cell = sync_at(px, w, mid)
            return x0, mid, cell
    return None


def decode(img):
    img = img.convert("RGB")
    found = find_strip(img)
    if not found:
        raise SystemExit("strip not found in the bottom 300 rows")
    x0, y0, cell = found
    w = img.size[0]
    # The corner block (v3) sits against the right edge, a longer baseline for the
    # cell size than the sync cells; an older full-width strip (v2) has 3 rows.
    fit = (w - x0) / COLS
    block = decode_at(img, x0, y0, fit if abs(fit - cell) <= cell * 0.15 else cell, COLS, MAX_ROWS)
    wide = int((w - x0) / cell + 0.01)
    if (block and block["crc_ok"]) or wide == COLS:
        res = block
    else:
        strip = decode_at(img, x0, y0, cell, wide, 3)
        res = strip if strip and strip["crc_ok"] else block
    if not res:
        raise SystemExit("strip found but unreadable")
    return res


def decode_at(img, x0, y0, cell, per_row, rows):
    px = img.load()

    def colour(i):
        row, col = divmod(i, per_row)
        return px[int(x0 + (col + 0.5) * cell), int(round(y0 - row * cell))][:3]

    palette = [colour(4 + p) for p in range(64)]

    def sym(i):
        c = colour(i)
        return min(range(64), key=lambda p: sum((a - b) ** 2 for a, b in zip(c, palette[p])))

    seq, version = sym(68), sym(69)
    length = (sym(70) << 6) | sym(71)
    nsyms = (length * 8 + 5) // 6
    if DATA + nsyms + 3 > per_row * rows:
        return None
    bits, nbits, out = 0, 0, bytearray()
    for i in range(DATA, DATA + nsyms):
        bits = (bits << 6) | sym(i)
        nbits += 6
        while nbits >= 8 and len(out) < length:
            nbits -= 8
            out.append((bits >> nbits) & 0xFF)
        bits &= (1 << nbits) - 1
    end = DATA + nsyms
    crc = (sym(end) << 12) | (sym(end + 1) << 6) | sym(end + 2)
    # Smallest distance between two calibration colours: how much margin is left.
    spread = min(sum((a - b) ** 2 for a, b in zip(palette[p], palette[q])) ** 0.5
                 for p in range(64) for q in range(p + 1, 64))
    return {"row": y0, "x": x0, "cell_px": round(cell, 2), "palette_min_distance": round(spread, 1),
            "seq": seq, "version": version, "length": length,
            "crc_ok": crc == crc16(bytes(out)), "payload": out.decode("utf-8", "replace")}


if __name__ == "__main__":
    res = decode(Image.open(sys.argv[1]))
    for k, v in res.items():
        print(f"{k}: {v}")
