#!/usr/bin/env python3
"""Draws the courier app's icons: a white location pin on the platform's primary blue (#0f62fe).

    python3 scripts/make-icons.py public

Writes public/icons/icon-192.png, icon-512.png, icon-maskable-512.png and public/favicon.ico.
Pure standard library (zlib + struct), 2x2 supersampling for edge smoothing. Deterministic: the
same bytes every run, so a regenerated icon is a reviewable diff, not noise. The maskable icon is
drawn smaller so the pin stays inside the 80% safe circle a launcher may crop to.
"""
import struct
import sys
import zlib

BLUE = (0x0F, 0x62, 0xFE)
WHITE = (0xFF, 0xFF, 0xFF)


def inside_pin(x, y):
    """Pin silhouette in unit coordinates, glyph centred on (0, 0)."""
    # head: circle
    if (x - 0.0) ** 2 + (y + 0.07) ** 2 <= 0.25**2:
        return True
    # tail: triangle tangent-ish to the head down to the tip
    if y >= 0.0 and y <= 0.34:
        half = 0.215 * (0.34 - y) / 0.34
        if abs(x) <= half:
            return True
    return False


def inside_hole(x, y):
    return x**2 + (y + 0.07) ** 2 <= 0.095**2


def render(size, glyph_scale):
    rows = []
    sub = 2
    for py in range(size):
        row = bytearray()
        for px in range(size):
            white = 0
            for sy in range(sub):
                for sx in range(sub):
                    x = ((px + (sx + 0.5) / sub) / size - 0.5) / glyph_scale
                    y = ((py + (sy + 0.5) / sub) / size - 0.5) / glyph_scale
                    if inside_pin(x, y) and not inside_hole(x, y):
                        white += 1
            t = white / (sub * sub)
            r = round(BLUE[0] + (WHITE[0] - BLUE[0]) * t)
            g = round(BLUE[1] + (WHITE[1] - BLUE[1]) * t)
            b = round(BLUE[2] + (WHITE[2] - BLUE[2]) * t)
            row += bytes((r, g, b, 255))
        rows.append(bytes(row))
    return rows


def png(size, rows):
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + row for row in rows)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def ico(png_bytes, size):
    header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack("<BBBBHHII", size, size, 0, 0, 1, 32, len(png_bytes), 22)
    return header + entry + png_bytes


out = sys.argv[1]
for name, size, scale in (("icon-192.png", 192, 1.0), ("icon-512.png", 512, 1.0), ("icon-maskable-512.png", 512, 0.76)):
    open(f"{out}/icons/{name}", "wb").write(png(size, render(size, scale)))
favicon = png(32, render(32, 1.0))
open(f"{out}/favicon.ico", "wb").write(ico(favicon, 32))
