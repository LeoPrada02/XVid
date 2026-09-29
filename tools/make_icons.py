"""Generate the PWA icons (a download arrow) without extra dependencies."""

import struct
import zlib
from pathlib import Path

BG = (15, 17, 21)
FG = (29, 155, 240)


def inside_arrow(x: float, y: float) -> bool:
    """Coordinates are 0..1. Kept within the central 60% so it survives maskable cropping."""
    stem = 0.44 <= x <= 0.56 and 0.24 <= y <= 0.55
    head = 0.52 <= y <= 0.70 and abs(x - 0.5) <= (0.70 - y) * 1.2
    tray = 0.74 <= y <= 0.80 and 0.28 <= x <= 0.72
    return stem or head or tray


def png(size: int) -> bytes:
    rows = []
    for py in range(size):
        row = bytearray([0])  # filter type: none
        for px in range(size):
            row += bytes(FG if inside_arrow((px + 0.5) / size, (py + 0.5) / size) else BG)
        rows.append(bytes(row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


if __name__ == "__main__":
    static = Path(__file__).resolve().parent.parent / "static"
    for size in (192, 512):
        (static / f"icon-{size}.png").write_bytes(png(size))
        print(f"wrote icon-{size}.png")
