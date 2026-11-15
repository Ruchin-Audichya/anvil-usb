#!/usr/bin/env python3
"""
Generates every Anvil brand asset from one set of anvil paths, so the master SVG,
the Android vector drawables and the PNGs never drift apart.

    python tools/brand/generate.py            # SVG + Android XML
    python tools/brand/generate.py --png      # also renders PNGs with headless Edge/Chrome

The artwork is drawn on a 64-unit grid (horn left, spark top right) and transformed
into each target's coordinate space here, so gradients use absolute coordinates.
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
BRAND = ROOT / "docs/brand"

# ---- artwork on the 64-unit grid -------------------------------------------------
# Each path is a list of (command, [x, y, x, y, ...]) with absolute M/L/C/Z only.
BODY = [("M", [3, 22]), ("C", [9, 19.2, 14, 18, 19, 18]), ("L", [58, 18]), ("L", [58, 25.5]),
        ("C", [52, 26, 47, 27.6, 45, 31.5]), ("L", [45, 38]), ("L", [52, 41]), ("L", [54, 48]),
        ("L", [12, 48]), ("L", [14, 41]), ("L", [21, 38]), ("L", [21, 31.5]),
        ("C", [19, 28.4, 13, 26, 3, 22]), ("Z", [])]
FACE = [("M", [19, 18]), ("L", [58, 18]), ("L", [58, 21]), ("L", [19.6, 21]),
        ("C", [15, 20.4, 11, 20.8, 6.5, 21.2]), ("C", [10.5, 19.2, 14.5, 18, 19, 18]), ("Z", [])]
BASE = [("M", [12, 48]), ("L", [14, 41]), ("L", [21, 38]), ("L", [45, 38]), ("L", [52, 41]), ("L", [54, 48]), ("Z", [])]
SPARK = [("M", [46, 2]), ("L", [47.3, 7.7]), ("L", [53, 9]), ("L", [47.3, 10.3]), ("L", [46, 16]),
         ("L", [44.7, 10.3]), ("L", [39, 9]), ("L", [44.7, 7.7]), ("Z", [])]
DOTS = [(36, 6, 1.5, "#FFB23F"), (55.5, 14.5, 1.1, "#FF8A2A"), (38.5, 13.5, 0.9, "#FFD27A")]

STEEL = [(0, "#B4C5DA"), (0.55, "#6E8098"), (1, "#3A4659")]
FACE_C = [(0, "#FFFFFF"), (1, "#D3DEEA")]
BASE_C = [(0, "#2E3A4B"), (1, "#1A212C")]
SPARK_C = [(0, "#FFF1C9"), (0.5, "#FFB23F"), (1, "#FF7A1A")]
BG_C = [(0, "#243142"), (1, "#0F151D")]


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


class T:
    """Uniform scale + translate from the 64-grid into a target space."""
    def __init__(self, s, tx, ty):
        self.s, self.tx, self.ty = s, tx, ty

    def x(self, v): return v * self.s + self.tx
    def y(self, v): return v * self.s + self.ty
    def d(self, v): return v * self.s

    def path(self, cmds, sep=" "):
        out = []
        for c, a in cmds:
            pts = []
            for i in range(0, len(a), 2):
                pts.append(f"{fmt(self.x(a[i]))},{fmt(self.y(a[i + 1]))}")
            out.append(c + sep.join(pts))
        return "".join(out)

    def circle(self, cx, cy, r):
        x, y, rr = self.x(cx), self.y(cy), self.d(r)
        return (f"M{fmt(x - rr)},{fmt(y)}a{fmt(rr)},{fmt(rr)} 0 1,0 {fmt(2 * rr)},0"
                f"a{fmt(rr)},{fmt(rr)} 0 1,0 {fmt(-2 * rr)},0Z")


# Artwork bbox on the grid: x 3..58, y 2..48 → centre (30.5, 25).
def centred(size, art_px):
    s = art_px / 56.0
    return T(s, size / 2 - 30.5 * s, size / 2 - 25 * s)


# ---- SVG -------------------------------------------------------------------------
def svg_stops(stops):
    return "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)


def svg_art(t, mono=None):
    if mono:
        return (f'<path fill="{mono}" d="{t.path(BODY)}"/><path fill="{mono}" d="{t.path(SPARK)}"/>'
                + "".join(f'<path fill="{mono}" d="{t.circle(cx, cy, r)}"/>' for cx, cy, r, _ in DOTS))
    y0, y1 = fmt(t.y(18)), fmt(t.y(48))
    return (
        "<defs>"
        f'<linearGradient id="anvil-body" gradientUnits="userSpaceOnUse" x1="0" y1="{y0}" x2="0" y2="{y1}">{svg_stops(STEEL)}</linearGradient>'
        f'<linearGradient id="anvil-face" gradientUnits="userSpaceOnUse" x1="{fmt(t.x(6))}" y1="0" x2="{fmt(t.x(58))}" y2="0">{svg_stops(FACE_C)}</linearGradient>'
        f'<linearGradient id="anvil-base" gradientUnits="userSpaceOnUse" x1="0" y1="{fmt(t.y(38))}" x2="0" y2="{fmt(t.y(48))}">{svg_stops(BASE_C)}</linearGradient>'
        f'<radialGradient id="anvil-spark" gradientUnits="userSpaceOnUse" cx="{fmt(t.x(46))}" cy="{fmt(t.y(9))}" r="{fmt(t.d(7))}">{svg_stops(SPARK_C)}</radialGradient>'
        "</defs>"
        f'<path fill="url(#anvil-body)" d="{t.path(BODY)}"/>'
        f'<path fill="url(#anvil-face)" d="{t.path(FACE)}"/>'
        f'<path fill="url(#anvil-base)" d="{t.path(BASE)}"/>'
        f'<path fill="url(#anvil-spark)" d="{t.path(SPARK)}"/>'
        + "".join(f'<path fill="{c}" d="{t.circle(cx, cy, r)}"/>' for cx, cy, r, c in DOTS)
    )


def master_svg():
    t = centred(108, 62)
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="128" height="128" role="img" aria-label="Anvil logo">'
        f'<defs><linearGradient id="anvil-bg" x1="0" y1="0" x2="0.35" y2="1">{svg_stops(BG_C)}</linearGradient></defs>'
        '<rect width="108" height="108" rx="24" fill="url(#anvil-bg)"/>'
        + svg_art(t) + "</svg>\n"
    )


def mark_svg():
    """Artwork only, transparent background (for the web and docs)."""
    t = centred(64, 56)
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" role="img" aria-label="Anvil">'
            + svg_art(t) + "</svg>\n")


# ---- Android vector drawables ----------------------------------------------------
A = 'xmlns:android="http://schemas.android.com/apk/res/android"'
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'


def vd_stops(stops):
    return "".join(f'\n                <item android:offset="{o}" android:color="#FF{c[1:]}" />' for o, c in stops)


def vd_linear(path, x1, y1, x2, y2, stops):
    return f'''
    <path android:pathData="{path}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="{fmt(x1)}" android:startY="{fmt(y1)}" android:endX="{fmt(x2)}" android:endY="{fmt(y2)}">{vd_stops(stops)}
            </gradient>
        </aapt:attr>
    </path>'''


def vd_radial(path, cx, cy, r, stops):
    return f'''
    <path android:pathData="{path}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="{fmt(cx)}" android:centerY="{fmt(cy)}" android:gradientRadius="{fmt(r)}">{vd_stops(stops)}
            </gradient>
        </aapt:attr>
    </path>'''


def foreground_xml():
    t = centred(108, 56)  # 56dp of artwork inside the 66dp safe zone
    body = vd_linear(t.path(BODY, ","), 0, t.y(18), 0, t.y(48), STEEL)
    face = vd_linear(t.path(FACE, ","), t.x(6), 0, t.x(58), 0, FACE_C)
    base = vd_linear(t.path(BASE, ","), 0, t.y(38), 0, t.y(48), BASE_C)
    spark = vd_radial(t.path(SPARK, ","), t.x(46), t.y(9), t.d(7), SPARK_C)
    dots = "".join(f'\n    <path android:fillColor="#FF{c[1:]}" android:pathData="{t.circle(cx, cy, r)}" />' for cx, cy, r, c in DOTS)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/brand/generate.py. Anvil 0.5 icon: gradient steel body, bright strike face,
     shaded base, spark cluster. 108dp adaptive canvas; artwork stays inside the 66dp safe zone. -->
<vector {A} {AAPT}
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">{body}{face}{base}{spark}{dots}
</vector>
'''


def background_xml():
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/brand/generate.py. -->
<vector {A} {AAPT}
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">{vd_linear("M0,0h108v108h-108z", 30, 0, 66, 108, BG_C)}
</vector>
'''


def silhouette_xml(size, art, comment, color="#FFFFFFFF"):
    t = centred(size, art)
    paths = [t.path(BODY, ","), t.path(SPARK, ",")] + [t.circle(cx, cy, r) for cx, cy, r, _ in DOTS[:1]]
    body = "".join(f'\n    <path android:fillColor="{color}" android:pathData="{p}" />' for p in paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/brand/generate.py. {comment} -->
<vector {A}
    android:width="{size}dp" android:height="{size}dp"
    android:viewportWidth="{size}" android:viewportHeight="{size}">{body}
</vector>
'''


# ---- PNG rendering ---------------------------------------------------------------
def browser():
    for p in [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
              r"C:\Program Files\Google\Chrome\Application\chrome.exe",
              shutil.which("chromium"), shutil.which("google-chrome"), shutil.which("msedge")]:
        if p and os.path.exists(p):
            return p
    return None


def render(html, out, w, h):
    exe = browser()
    if not exe:
        sys.exit("No Edge/Chrome found for PNG rendering")
    with tempfile.TemporaryDirectory() as d:
        src = Path(d) / "page.html"
        src.write_text(html, encoding="utf-8")
        shot = Path(d) / "shot.png"
        subprocess.run([exe, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                        "--default-background-color=00000000", f"--window-size={w},{h}", f"--user-data-dir={d}/profile",
                        "--virtual-time-budget=4000", f"--screenshot={shot}", src.as_uri()],
                       check=True, capture_output=True, timeout=120)
        shutil.copyfile(shot, out)
    print("rendered", out.relative_to(ROOT), f"{w}x{h}")


def icon_page(px):
    svg = master_svg().replace('width="128" height="128"', 'width="%d" height="%d"' % (px, px))
    return ('<!doctype html><html><body style="margin:0;background:transparent">'
            '<div style="width:%dpx;height:%dpx">%s</div></body></html>' % (px, px, svg))


def social_page():
    mark = master_svg().replace('width="128" height="128"', 'width="300" height="300"')
    return f'''<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Big+Shoulders+Display:wght@900&family=Hanken+Grotesk:wght@500;600&display=swap">
<style>
  html,body{{margin:0;width:1280px;height:640px;overflow:hidden}}
  body{{background:radial-gradient(70% 90% at 78% 0%,#2A3647 0%,#121820 62%,#0C1015 100%);color:#E5EAF0;
       font-family:"Hanken Grotesk","Segoe UI",sans-serif;display:flex;align-items:center;gap:64px;padding:0 96px;box-sizing:border-box}}
  .glow{{position:absolute;right:-120px;top:-220px;width:620px;height:620px;border-radius:50%;
        background:radial-gradient(closest-side,rgba(255,178,63,.22),transparent)}}
  h1{{font:900 168px/0.85 "Big Shoulders Display",Impact,"Arial Narrow",sans-serif;letter-spacing:.08em;text-transform:uppercase;margin:0}}
  p{{font-size:34px;line-height:1.3;margin:22px 0 0;color:#B9C4D2;max-width:640px}}
  .tag{{margin-top:30px;font-size:22px;color:#FFB23F;letter-spacing:.14em;text-transform:uppercase;font-weight:600}}
  svg{{flex:none;filter:drop-shadow(0 18px 40px rgba(0,0,0,.5))}}
</style></head><body><div class="glow"></div>{mark}
<div><h1>Anvil</h1><p>Forge a bootable Windows or Linux USB from your Android phone. No PC, no root.</p>
<div class="tag">Official ISOs · verified · open source</div></div></body></html>'''


def main():
    BRAND.mkdir(parents=True, exist_ok=True)
    master = master_svg()
    (BRAND / "anvil-icon.svg").write_text(master, encoding="utf-8")
    (ROOT / "docs/anvil-icon.svg").write_text(master, encoding="utf-8")  # README / old links
    (BRAND / "anvil-mark.svg").write_text(mark_svg(), encoding="utf-8")
    (RES / "drawable/ic_launcher_foreground.xml").write_text(foreground_xml(), encoding="utf-8")
    (RES / "drawable/ic_launcher_background.xml").write_text(background_xml(), encoding="utf-8")
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(
        silhouette_xml(108, 56, "Single-colour silhouette for Android 13+ themed icons (tinted by the launcher)."), encoding="utf-8")
    (RES / "drawable/ic_notification.xml").write_text(
        silhouette_xml(24, 21, "Status-bar icon: the ic_launcher_monochrome silhouette on a 24dp viewport."), encoding="utf-8")
    print("wrote SVG + Android vector drawables")
    if "--png" in sys.argv:
        render(icon_page(512), BRAND / "anvil-icon-512.png", 512, 512)
        render(icon_page(1024), BRAND / "anvil-icon-1024.png", 1024, 1024)
        render(social_page(), BRAND / "social-preview.png", 1280, 640)


if __name__ == "__main__":
    main()
