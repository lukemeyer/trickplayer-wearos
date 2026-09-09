#!/usr/bin/env python3
"""
Mean luminance of a watch-face screenshot, whole and per band.

Written because "ambient looks darker" is not a measurement, and because the
obvious eyeball check is unreliable here: pressing HOME while the watch face is
showing opens the app launcher, so it is entirely possible to doze the launcher
by accident and screenshot that instead. The launcher's white circles are bright,
so a naive brightness check calls it a working interactive face. Use KEYCODE_BACK
to leave the launcher, and confirm the frame band actually goes dark.

No PIL on this machine, so the PNG is decoded here — non-interlaced, 8-bit only,
which is what `adb exec-out screencap -p` produces.

    python3 spikes/tools/luminance.py shots/14-ambient.png
"""
import zlib, struct, sys


def read_png(path):
    d = open(path, 'rb').read()
    pos, idat = 8, b''
    w = h = ct = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos + 4])[0]
        typ, body = d[pos + 4:pos + 8], d[pos + 8:pos + 8 + ln]
        if typ == b'IHDR':
            w, h, _bd, ct = struct.unpack('>IIBB', body[:10])
        elif typ == b'IDAT':
            idat += body
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    stride = w * ch
    out, prev, i = bytearray(), bytearray(stride), 0
    for _ in range(h):                       # undo per-scanline filters
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        for x in range(stride):
            a = line[x - ch] if x >= ch else 0
            b = prev[x]
            c = prev[x - ch] if x >= ch else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if (pa <= pb and pa <= pc) else (b if pb <= pc else c))) & 255
        out += line; prev = line
    return w, h, ch, bytes(out)


def main(path):
    w, h, ch, px = read_png(path)
    s = h / 450.0                            # design canvas -> panel

    def stats(y0, y1, x0, x1):
        n = total = mx = 0
        for y in range(int(y0 * s), int(y1 * s)):
            for x in range(int(x0 * s), int(x1 * s)):
                i = (y * w + x) * ch
                L = (299 * px[i] + 587 * px[i + 1] + 114 * px[i + 2]) // 1000
                total += L; n += 1; mx = max(mx, L)
        return total / n, mx

    frame_mean, frame_max = stats(82, 307, 25, 425)
    sub_mean, _ = stats(315, 391, 80, 370)
    whole = sum((299 * px[i] + 587 * px[i + 1] + 114 * px[i + 2]) // 1000
                for i in range(0, len(px), ch)) / (w * h)
    print(f"frame_mean={frame_mean:.1f} frame_max={frame_max} "
          f"sub_mean={sub_mean:.1f} whole_mean={whole:.1f}")


if __name__ == '__main__':
    main(sys.argv[1])
