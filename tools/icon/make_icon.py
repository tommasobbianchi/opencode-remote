#!/usr/bin/env python3
"""Genera le icone adattive dell'app da un render PNG quadrato.

Uso: python3 make_icon.py source.png outdir
Produce:
  out/ic_launcher_foreground_<dens>.png  (108/162/216/324/432 px, con alpha)
  out/ic_launcher_<dens>.png             (legacy, angoli arrotondati, 48..192)
  out/ic_launcher_round_<dens>.png       (legacy rotondo)
  out/ic_launcher_monochrome_<dens>.png  (silhouette bianca per icone tematizzate)
  out/preview.png
  out/background.txt                     (hex del colore di sfondo)
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_PATH = os.environ.get("MONO_FONT", "mono.ttf")
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def plate_box(im, thr=8):
    """Bbox del contenuto (la 'piastra' arrotondata) escludendo il nero esterno."""
    mask = im.convert("L").point(lambda v: 255 if v > thr else 0)
    return mask.getbbox()


def ring_color(im, box, inset_ratio=0.02, band_ratio=0.06):
    """Colore medio di una cornice interna alla piastra: il fondo su cui poggia il render."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    inset = int(min(w, h) * inset_ratio)
    band = int(min(w, h) * band_ratio)
    region = im.crop((x0 + inset, y0 + inset, x1 - inset, y1 - inset))
    px = region.load()
    rw, rh = region.size
    acc = [0, 0, 0, 0]
    for x in range(rw):
        for y in (list(range(band)) + list(range(rh - band, rh))):
            p = px[x, y]
            acc[0] += p[0]; acc[1] += p[1]; acc[2] += p[2]; acc[3] += 1
    for y in range(rh):
        for x in (list(range(band)) + list(range(rw - band, rw))):
            p = px[x, y]
            acc[0] += p[0]; acc[1] += p[1]; acc[2] += p[2]; acc[3] += 1
    n = max(acc[3], 1)
    return (acc[0] // n, acc[1] // n, acc[2] // n)


def radial_alpha(size, feather=0.14, radius_ratio=0.5):
    """Maschera circolare con bordo sfumato (evita spigoli visibili sulla maschera del launcher)."""
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    margin = int(size * feather)
    d.ellipse((margin, margin, size - margin, size - margin), fill=255)
    blur = max(1, size // 24)
    return mask.filter(ImageFilter.GaussianBlur(blur))


def squircle_mask(size, radius_ratio=0.22):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * radius_ratio), fill=255)
    return mask


def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def split_mark(art, min_gap_ratio=0.03, floor=45):
    """Separa il marchio dall'eventuale wordmark sotto (banda scura più larga).

    Il launcher adattivo non deve contenere testo: un wordmark verrebbe tagliato
    dalla maschera circolare. Restituisce (immagine del marchio, ha_wordmark).
    """
    g = art.convert("L")
    w, h = g.size
    px = g.load()
    step = 2
    rows = [max(px[x, y] for x in range(0, w, step)) for y in range(h)]
    content = [i for i, v in enumerate(rows) if v > 90]
    if not content:
        return art, False
    # Wordmark: prima riga in cui il verde domina (testo neon), altrimenti banda scura.
    rgb = art.load()
    green_top = None
    for y in range(content[0], content[-1] + 1):
        count = 0
        for x in range(0, w, step):
            r, gg, b = rgb[x, y][:3]
            if gg > 120 and gg > r + 40 and gg > b + 40:
                count += 1
        if count > w * 0.01:
            green_top = y
            break
    if green_top is not None and green_top > h * 0.3:
        return art.crop((0, 0, w, max(0, green_top - int(h * 0.015)))), True
    best = None
    run_start = None
    for y in range(content[0], content[-1] + 1):
        if rows[y] <= floor:
            if run_start is None:
                run_start = y
        elif run_start is not None:
            if best is None or (y - run_start) > (best[1] - best[0]):
                best = (run_start, y)
            run_start = None
    if run_start is not None and (best is None or (content[-1] + 1 - run_start) > (best[1] - best[0])):
        best = (run_start, content[-1] + 1)
    if not best or (best[1] - best[0]) < h * min_gap_ratio:
        return art, False
    if not any(v > 90 for v in rows[best[1]:]):
        return art, False
    return art.crop((0, 0, w, best[0])), True


def luma_alpha(im, floor=14, span=80):
    """Alpha dalla luminanza: il fondo scuro diventa trasparente, il neon resta."""
    g = im.convert("L")
    a = g.point(lambda v: 0 if v <= floor else min(255, int((v - floor) * 255 / span)))
    return a.filter(ImageFilter.GaussianBlur(max(1, im.size[0] // 240)))


def square_crop(im, thr=90, margin=1.04):
    """Ritaglia sul contenuto luminoso e centra in una tela quadrata."""
    b = im.convert("L").point(lambda v: 255 if v > thr else 0).getbbox()
    if b:
        im = im.crop(b)
    side = int(max(im.size) * margin)
    sq = Image.new("RGB", (side, side), (0, 0, 0))
    sq.paste(im, ((side - im.size[0]) // 2, (side - im.size[1]) // 2))
    return sq


def main(src, out):
    os.makedirs(out, exist_ok=True)
    im = Image.open(src).convert("RGB")
    plate = plate_box(im)
    bg = ring_color(im, plate)
    # Il glifo sta dentro la piastra: bbox misurato sulla luminanza, escludendo il bordo.
    px0, py0, px1, py1 = plate
    inset = int(min(px1 - px0, py1 - py0) * 0.10)
    inner = im.crop((px0 + inset, py0 + inset, px1 - inset, py1 - inset))
    gb = inner.convert("L").point(lambda v: 255 if v > 90 else 0).getbbox()
    gx0, gy0, gx1, gy1 = gb[0] + px0 + inset, gb[1] + py0 + inset, gb[2] + px0 + inset, gb[3] + py0 + inset
    side = int(max(gx1 - gx0, gy1 - gy0) * 1.10)
    cx, cy = (gx0 + gx1) // 2, (gy0 + gy1) // 2
    box = (cx - side // 2, cy - side // 2, cx + side // 2, cy + side // 2)
    art = im.crop(box)
    bg = ring_color(art, (0, 0, art.size[0], art.size[1]), inset_ratio=0.0, band_ratio=0.04)

    with open(os.path.join(out, "background.txt"), "w") as fh:
        fh.write("#%02X%02X%02X\n" % bg)

    # --- foreground adattivo: solo il marchio (niente wordmark), alpha dal neon
    mark, has_wordmark = split_mark(art)
    mark = square_crop(mark)
    for dens, scale in DENSITIES.items():
        canvas = int(108 * scale)          # 108dp
        art_size = int(canvas * 0.66)      # dentro la safe zone della maschera
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        piece = mark.resize((art_size, art_size), Image.LANCZOS).convert("RGBA")
        piece.putalpha(luma_alpha(piece))
        off = (canvas - art_size) // 2
        layer.alpha_composite(piece, (off, off))
        layer.save(os.path.join(out, f"ic_launcher_foreground_{dens}.png"))

    # --- legacy (pre-Android 8): piastra intera, angoli arrotondati / cerchio
    for dens, px in LEGACY.items():
        plate_img = im.crop(plate)
        sq = plate_img.resize((px, px), Image.LANCZOS).convert("RGBA")
        sq.putalpha(squircle_mask(px, 0.22))
        sq.save(os.path.join(out, f"ic_launcher_{dens}.png"))
        rd_canvas = Image.new("RGBA", (px, px), bg + (255,))
        inner_px = int(px * 0.94)
        rd_piece = plate_img.resize((inner_px, inner_px), Image.LANCZOS).convert("RGBA")
        rd_canvas.alpha_composite(rd_piece, ((px - inner_px) // 2, (px - inner_px) // 2))
        rd_canvas.putalpha(circle_mask(px))
        rd_canvas.save(os.path.join(out, f"ic_launcher_round_{dens}.png"))

        # monochrome per icone tematizzate: "OC" nel font dell'app (JetBrains Mono)
        mono = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        d = ImageDraw.Draw(mono)
        font = ImageFont.truetype(FONT_PATH, int(px * 0.50))
        text = "OC"
        tb = d.textbbox((0, 0), text, font=font)
        d.text(((px - (tb[2] - tb[0])) // 2 - tb[0], (px - (tb[3] - tb[1])) // 2 - tb[1]),
               text, font=font, fill=(255, 255, 255, 255))
        mono.save(os.path.join(out, f"ic_launcher_monochrome_{dens}.png"))

    # --- anteprima: cerchio adattivo, squircle, legacy, monochrome, su due fondi
    S = 432
    def adaptive():
        bgc = Image.new("RGBA", (S, S), bg + (255,))
        fg = Image.open(os.path.join(out, "ic_launcher_foreground_xxxhdpi.png")).convert("RGBA")
        bgc.alpha_composite(fg)
        return bgc

    def masked(img, mask):
        out_img = Image.new("RGBA", img.size, (0, 0, 0, 0))
        out_img.paste(img, (0, 0), mask)
        return out_img

    ad = adaptive()
    tiles = [
        masked(ad, circle_mask(S)),
        masked(ad, squircle_mask(S, 0.34)),
        Image.open(os.path.join(out, "ic_launcher_xxxhdpi.png")).convert("RGBA").resize((S, S), Image.LANCZOS),
        Image.open(os.path.join(out, "ic_launcher_round_xxxhdpi.png")).convert("RGBA").resize((S, S), Image.LANCZOS),
    ]
    mono = Image.open(os.path.join(out, "ic_launcher_monochrome_xxxhdpi.png")).convert("RGBA").resize((S, S), Image.LANCZOS)
    mono_tile = Image.new("RGBA", (S, S), (232, 234, 237, 255))
    mono_tile.alpha_composite(mono)
    tiles.append(masked(mono_tile, squircle_mask(S, 0.34)))

    pad = 28
    W = len(tiles) * (S + pad) + pad
    H = 2 * (S + pad) + pad
    preview = Image.new("RGBA", (W, H), (18, 18, 18, 255))
    for i, t in enumerate(tiles):
        preview.alpha_composite(t, (pad + i * (S + pad), pad))
    # seconda riga: su fondo chiaro
    preview.paste((242, 244, 246, 255), (0, S + 2 * pad, W, H))
    for i, t in enumerate(tiles):
        preview.alpha_composite(t, (pad + i * (S + pad), S + 2 * pad))
    preview.save(os.path.join(out, "preview.png"))
    print("ok", out, "bg=#%02X%02X%02X" % bg, "box=", box)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
