#!/usr/bin/env python3
"""生成应用启动图标 PNG（纯标准库实现，无 PIL 依赖）。

输出到 app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
ic_launcher.png 与 ic_launcher_round.png（圆形裁剪）。
"""
import os
import struct
import zlib

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

SS = 4  # 超采样倍数，用于抗锯齿


def rounded_rect_mask(x, y, left, top, right, bottom, radius):
    """点 (x, y) 是否在圆角矩形内"""
    if not (left <= x <= right and top <= y <= bottom):
        return False
    cx = min(max(x, left + radius), right - radius)
    cy = min(max(y, top + radius), bottom - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2


def render(size):
    s = size * SS
    buf = bytearray(s * s * 4)

    def set_px(ix, iy, r, g, b, a):
        o = (iy * s + ix) * 4
        buf[o] = r
        buf[o + 1] = g
        buf[o + 2] = b
        buf[o + 3] = a

    for iy in range(s):
        fy = iy / s
        # 背景渐变 #4F6BFF -> #7C4DFF
        t = fy
        br = int(0x4F + (0x7C - 0x4F) * t)
        bg = int(0x6B + (0x4D - 0x6B) * t)
        bb = int(0xFF + (0xFF - 0xFF) * t)
        # 圆角背景（圆形图标时由调用方裁剪）
        bg_radius = 0.225 * s
        for ix in range(s):
            fx = ix / s
            inside_bg = rounded_rect_mask(fx, fy, 0.0, 0.0, 1.0, 1.0, 0.225)
            if not inside_bg:
                set_px(ix, iy, 0, 0, 0, 0)
                continue

            # 大手机轮廓（描边）：0.22-0.60 x, 0.16-0.78 y
            phone_l, phone_t, phone_r, phone_b = 0.20, 0.14, 0.58, 0.80
            outer = rounded_rect_mask(fx, fy, phone_l, phone_t, phone_r, phone_b, 0.045 * s)
            inner = rounded_rect_mask(fx, fy, phone_l + 0.035, phone_t + 0.035,
                                      phone_r - 0.035, phone_b - 0.035, 0.03 * s)
            # 小远程屏（实心）：0.50-0.84 x, 0.42-0.88 y
            small = rounded_rect_mask(fx, fy, 0.50, 0.44, 0.84, 0.86, 0.04 * s)
            small_inner = rounded_rect_mask(fx, fy, 0.535, 0.475, 0.805, 0.825, 0.02 * s)

            r, g, b, a = br, bg, bb, 255
            if outer and not inner:
                r, g, b = 255, 255, 255
            elif small:
                r, g, b = 255, 255, 255
                if small_inner:
                    r, g, b = br, bg, bb
            set_px(ix, iy, r, g, b, a)

    # 盒式降采样抗锯齿
    out = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for sy in range(SS):
                for sx in range(SS):
                    o = ((y * SS + sy) * s + (x * SS + sx)) * 4
                    r += buf[o]
                    g += buf[o + 1]
                    b += buf[o + 2]
                    a += buf[o + 3]
            n = SS * SS
            oo = (y * size + x) * 4
            out[oo] = r // n
            out[oo + 1] = g // n
            out[oo + 2] = b // n
            out[oo + 3] = a // n
    return out


def to_circular(data, size):
    out = bytearray(data)
    c = size / 2
    for y in range(size):
        for x in range(size):
            if (x + 0.5 - c) ** 2 + (y + 0.5 - c) ** 2 > c * c:
                out[(y * size + x) * 4 + 3] = 0
    return out


def write_png(path, data, size):
    def chunk(tag, payload):
        c = struct.pack(">I", len(payload)) + tag + payload
        return c + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)

    raw = b"".join(
        b"\x00" + data[y * size * 4:(y + 1) * size * 4] for y in range(size)
    )
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


def main():
    for density, size in DENSITIES.items():
        d = os.path.join(RES, "mipmap-" + density)
        os.makedirs(d, exist_ok=True)
        data = render(size)
        write_png(os.path.join(d, "ic_launcher.png"), data, size)
        write_png(os.path.join(d, "ic_launcher_round.png"), to_circular(data, size), size)
        print(f"mipmap-{density}: {size}x{size} ok")


if __name__ == "__main__":
    main()
