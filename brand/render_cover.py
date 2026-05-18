"""Render Mira hackathon cover PNGs (1280x720) by reproducing mira-lockup.svg
in PIL. We do this natively because libcairo (cairosvg) and rsvg-convert are
not available on this machine.

Geometry mirrors brand/mira-lockup.svg exactly:
  - SVG viewBox: 320 x 100
  - Inner group: translate(8 18) scale(1.33)
    - circle cx=24 cy=24 r=22 stroke #2A4A4D stroke-width 1.75
    - 6 triangles at rotations 0/60/120/180/240/300 deg around (24,24)
      triangle path: M 24 4.5 L 38.7 21 L 24 21 Z
      filled #2A4A4D with alternating opacity 0.18 / 0.24, plus a
      stroke #2A4A4D stroke-width 0.9 stroke-linejoin round opacity 0.85
    - inner circle cx=24 cy=24 r=5.5 fill #C97A4A
  - Text: x=110 y=71 font-size 74 Instrument Serif Italic (Georgia Italic fallback)
    fill #2A4A4D, letter-spacing -0.7

Output: 1280x720 cream canvas, lockup ~800px wide centered horizontally.
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---- Brand constants -------------------------------------------------------
TEAL = (42, 74, 77)          # #2A4A4D
ORANGE = (201, 122, 74)      # #C97A4A
CREAM = (250, 248, 243)      # #FAF8F3

# Canvas
W, H = 1280, 720

# Lockup target width on the canvas (per spec, ~800px)
LOCKUP_W = 800
# SVG aspect is 320x100, so target height:
LOCKUP_H = int(round(LOCKUP_W * 100 / 320))  # 250

# Scale factor from SVG units to lockup-image pixels
S = LOCKUP_W / 320.0  # 2.5

# Supersample factor for the mark (anti-aliasing for circles + triangles)
SS = 4

# Output dir
OUT = Path("/Users/pere/Desktop/AI Projects/Mira-Hackathon")


def _rotate(px: float, py: float, cx: float, cy: float, deg: float) -> tuple[float, float]:
    """Rotate point (px,py) around (cx,cy) by deg degrees (matches SVG)."""
    rad = math.radians(deg)
    dx, dy = px - cx, py - cy
    cos_a, sin_a = math.cos(rad), math.sin(rad)
    return (cx + dx * cos_a - dy * sin_a, cy + dx * sin_a + dy * cos_a)


def render_lockup() -> Image.Image:
    """Render the lockup into a transparent RGBA image of size LOCKUP_W x LOCKUP_H."""
    # Supersampled canvas for the mark
    ssW, ssH = LOCKUP_W * SS, LOCKUP_H * SS
    base = Image.new("RGBA", (ssW, ssH), (0, 0, 0, 0))
    draw = ImageDraw.Draw(base)

    # Pixel-per-SVG-unit at supersampled scale
    s = S * SS  # 10.0 at SS=4

    # The mark's inner group has SVG-level transform: translate(8, 18) scale(1.33)
    # Inside that group, the circle center is (24, 24) with r=22, etc.
    # Compose: pixel = (svg_unit * 1.33 + offset_xy) * s
    def to_px(x: float, y: float) -> tuple[float, float]:
        return ((8 + x * 1.33) * s, (18 + y * 1.33) * s)

    def to_len(v: float) -> float:
        return v * 1.33 * s

    # Outer circle (cx=24 cy=24 r=22, stroke #2A4A4D, stroke-width 1.75)
    ccx, ccy = to_px(24, 24)
    cr = to_len(22)
    cw = to_len(1.75)
    draw.ellipse(
        [ccx - cr, ccy - cr, ccx + cr, ccy + cr],
        outline=TEAL + (255,),
        width=max(1, int(round(cw))),
    )

    # Triangle path in SVG units (pre-rotation), points:
    # M 24 4.5  L 38.7 21  L 24 21 Z
    tri_pts = [(24.0, 4.5), (38.7, 21.0), (24.0, 21.0)]
    rotations = [0, 60, 120, 180, 240, 300]
    # SVG ordering: index 0,2,4 -> 0.18 fill; 1,3,5 -> 0.24 fill
    fill_opacities = [0.18, 0.24, 0.18, 0.24, 0.18, 0.24]

    # Filled triangles
    for deg, opacity in zip(rotations, fill_opacities):
        pts_rot = [_rotate(px, py, 24, 24, deg) for (px, py) in tri_pts]
        pts_pix = [to_px(x, y) for (x, y) in pts_rot]
        alpha = int(round(255 * opacity))
        draw.polygon(pts_pix, fill=TEAL + (alpha,))

    # Stroked triangle overlay (stroke-width 0.9, opacity 0.85, linejoin round)
    stroke_alpha = int(round(255 * 0.85))
    stroke_w = max(1, int(round(to_len(0.9))))
    for deg in rotations:
        pts_rot = [_rotate(px, py, 24, 24, deg) for (px, py) in tri_pts]
        pts_pix = [to_px(x, y) for (x, y) in pts_rot]
        # close the polygon by repeating first point
        closed = pts_pix + [pts_pix[0]]
        draw.line(closed, fill=TEAL + (stroke_alpha,), width=stroke_w, joint="curve")

    # Inner orange dot (cx=24 cy=24 r=5.5 #C97A4A)
    dr = to_len(5.5)
    draw.ellipse(
        [ccx - dr, ccy - dr, ccx + dr, ccy + dr],
        fill=ORANGE + (255,),
    )

    # Downsample mark to lockup-size for crisp edges
    mark_img = base.resize((LOCKUP_W, LOCKUP_H), Image.LANCZOS)

    # ---- Wordmark text -----------------------------------------------------
    # SVG: text x=110 y=71 font-size 74, italic serif, letter-spacing -0.7
    # In lockup pixel space: each SVG unit = S px (2.5)
    text_x_px = 110 * S
    text_baseline_y_px = 71 * S
    font_size_px = int(round(74 * S))  # 185

    # Render text into the same lockup image
    font_path = "/System/Library/Fonts/Supplemental/Georgia Italic.ttf"
    font = ImageFont.truetype(font_path, font_size_px)

    text_draw = ImageDraw.Draw(mark_img)

    # SVG text y is the baseline. PIL with anchor="ls" sets (x,y) to left-baseline.
    # Apply letter-spacing manually: -0.7 SVG units => -0.7 * S px between chars.
    letter_spacing_px = -0.7 * S  # ~ -1.75 px
    text = "mira"
    pen_x = text_x_px
    for ch in text:
        text_draw.text((pen_x, text_baseline_y_px), ch, font=font, fill=TEAL, anchor="ls")
        # Measure advance using getlength (horizontal advance for this glyph)
        adv = font.getlength(ch)
        pen_x += adv + letter_spacing_px

    return mark_img


def make_canvas() -> Image.Image:
    return Image.new("RGB", (W, H), CREAM)


def compose_cover(with_tagline: bool, out_path: Path) -> tuple[int, int]:
    canvas = make_canvas()
    lockup = render_lockup()

    if with_tagline:
        # Position lockup centered horizontally, slightly above vertical center.
        # Spec: top edge around y=240-290. We'll choose y=255 to leave room
        # for the tagline below while keeping eye-line in upper third.
        lockup_x = (W - LOCKUP_W) // 2  # 240
        lockup_y = 255
    else:
        # Perfectly centered (both axes)
        lockup_x = (W - LOCKUP_W) // 2          # 240
        lockup_y = (H - LOCKUP_H) // 2          # 235

    canvas.paste(lockup, (lockup_x, lockup_y), lockup)

    if with_tagline:
        tagline = "Offline cervical cancer screening, for the clinics that need it most."
        font_path = "/System/Library/Fonts/Supplemental/Georgia Italic.ttf"
        # Note: at 1280x720, pt ~= px for our purposes (PIL uses pixel sizes).
        # Spec: 36-42pt. Use 40px.
        tag_font = ImageFont.truetype(font_path, 40)

        draw = ImageDraw.Draw(canvas)
        # Baseline ~ 100-130 px below the lockup bottom edge
        # Lockup bottom is at lockup_y + LOCKUP_H
        tag_baseline_y = lockup_y + LOCKUP_H + 115
        tag_x = W // 2

        # Center horizontally using textlength; anchor on baseline ("ms").
        draw.text((tag_x, tag_baseline_y), tagline, font=tag_font, fill=TEAL, anchor="ms")

    canvas.save(out_path, format="PNG", optimize=True)
    return canvas.size


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cover_path = OUT / "cover.png"
    minimal_path = OUT / "cover-minimal.png"

    size1 = compose_cover(with_tagline=True, out_path=cover_path)
    size2 = compose_cover(with_tagline=False, out_path=minimal_path)

    print(f"cover.png: {size1}, {cover_path.stat().st_size} bytes")
    print(f"cover-minimal.png: {size2}, {minimal_path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
