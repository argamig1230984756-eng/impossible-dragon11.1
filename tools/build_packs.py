#!/usr/bin/env python3
"""
Builds, from ONE shared model spec:
  * Java resource pack  (item models for ItemDisplay entities + blanked vanilla dragon/endermite textures)
  * Bedrock resource pack (real geometry replacing ender_dragon / endermite / wither_skull / ender_crystal)
  * models.json for the plugin (part list, joints, custom-model-data ids)
"""
import json, math, os, random, shutil, sys, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
PLUGIN_RES = sys.argv[2] if len(sys.argv) > 2 else None
NS = "impossibledragon"
DRAGON_TEX = os.environ.get("DRAGON_TEX")          # path to Better Enderdragon dragon.png
SKIP_DRAGON = bool(DRAGON_TEX)                     # dragon itself = vanilla mesh + that texture, so no custom dragon model

# ------------------------------------------------------------------ textures
rng = np.random.default_rng(7)
TILE = 32
MATS = ["dark", "dark2", "plate", "membrane", "spike", "eye"]


def noise(base, amp, size=TILE):
    n = rng.normal(0, amp, (size, size, 1))
    img = np.clip(np.array(base, dtype=float)[None, None, :] + n, 0, 255)
    return img


def tex_dark():
    img = noise((30, 27, 38), 7)
    for _ in range(26):  # lighter speckles (blackstone gilding)
        x, y = rng.integers(0, TILE, 2)
        img[y, x] = (62, 52, 76)
    for _ in range(10):  # violet flecks
        x, y = rng.integers(0, TILE, 2)
        img[y, x] = (110, 60, 170)
    return img


def tex_dark2():
    img = noise((42, 38, 52), 5)
    for i in range(0, TILE, 8):
        img[i, :] = (20, 18, 28)
    for r, i in enumerate(range(0, TILE, 8)):
        off = 0 if r % 2 == 0 else 8
        for x in range(off, TILE, 16):
            img[i:i + 8, x] = (20, 18, 28)
    return img


def tex_plate():
    img = noise((52, 46, 66), 4)
    img[0, :] = img[-1, :] = (90, 70, 120)
    img[:, 0] = img[:, -1] = (90, 70, 120)
    return img


def tex_membrane():
    img = noise((38, 33, 56), 5)
    for _ in range(5):  # purple veins
        x, y = rng.integers(0, TILE, 2)
        dx, dy = rng.choice([-1, 0, 1], 2)
        for _ in range(14):
            img[y % TILE, x % TILE] = (120, 70, 190)
            x += dx + int(rng.integers(-1, 2)); y += dy + int(rng.integers(-1, 2))
    return img


def tex_spike():
    img = np.zeros((TILE, TILE, 3))
    for y in range(TILE):
        for x in range(TILE):
            f = (x + y) / (2 * TILE)
            img[y, x] = (118 + 90 * f, 70 + 70 * f, 190 + 50 * f)
    img += rng.normal(0, 6, (TILE, TILE, 1))
    for i in range(0, TILE, 11):
        img[i:i + 2, :] += 18
    for _ in range(14):
        x, y = rng.integers(0, TILE, 2)
        img[y, x] = (245, 215, 255)
    return np.clip(img, 0, 255)


def tex_eye():
    img = np.zeros((TILE, TILE, 3))
    yy, xx = np.mgrid[0:TILE, 0:TILE]
    d = np.sqrt((xx - 16) ** 2 + (yy - 16) ** 2) / 16
    for c, (a, b) in enumerate(zip((255, 70, 235), (255, 235, 255))):
        img[:, :, c] = a + (b - a) * np.clip(1 - d, 0, 1)
    return np.clip(img, 0, 255)


def sampled_tiles():
    """pull matching tiles out of the user's dragon texture so minions / skull heads share its look"""
    im = np.array(Image.open(DRAGON_TEX).convert("RGBA")).astype(float)
    W = 16
    cands = []
    for y in range(0, im.shape[0] - W, 2):
        for x in range(0, im.shape[1] - W, 2):
            win = im[y:y + W, x:x + W]
            if (win[:, :, 3] < 255).any():
                continue
            rgb = win[:, :, :3]
            lum = rgb.mean()
            sat = (rgb.max(axis=2) - rgb.min(axis=2)).mean()
            cands.append((lum, sat, x, y))
    byl = sorted(cands, key=lambda c: c[0])
    bys = sorted(cands, key=lambda c: c[1])
    def far(pool, taken):
        for c in pool:
            if all(abs(c[2] - t[2]) + abs(c[3] - t[3]) > 24 for t in taken):
                return c
        return pool[0]
    taken = []
    picks = {}
    picks["dark"] = far(byl, taken); taken.append(picks["dark"])
    picks["dark2"] = far(byl[len(byl) // 4:], taken); taken.append(picks["dark2"])
    picks["plate"] = far(byl[len(byl) // 2:], taken); taken.append(picks["plate"])
    picks["membrane"] = far(bys[int(len(bys) * .85):], taken); taken.append(picks["membrane"])
    picks["spike"] = far(sorted(cands, key=lambda c: -(im[c[3]:c[3] + W, c[2]:c[2] + W, 0].mean() + im[c[3]:c[3] + W, c[2]:c[2] + W, 2].mean())), taken)
    out = {}
    for name, (_, _, x, y) in picks.items():
        tile = im[y:y + W, x:x + W, :3]
        out[name] = (lambda t: (lambda: np.kron(t, np.ones((2, 2, 1)))))(tile)
    return out


def save_rgb(arr, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.clip(arr, 0, 255).astype("uint8"), "RGB").save(path)


def make_ring(size=64):
    yy, xx = np.mgrid[0:size, 0:size]
    d = np.sqrt((xx - size / 2 + .5) ** 2 + (yy - size / 2 + .5) ** 2) / (size / 2)
    a = np.zeros((size, size))
    inside = d <= 1
    a[inside] = 0.07 + 0.22 * np.clip(d[inside], 0, 1) ** 3
    a += np.where((d > 0.9) & (d <= 1.0), 0.6 * (1 - np.abs(d - 0.95) / 0.05), 0)
    img = np.zeros((size, size, 4), dtype="uint8")
    img[:, :, 0], img[:, :, 1], img[:, :, 2] = 170, 100, 255
    img[:, :, 3] = np.clip(a * 255, 0, 255).astype("uint8")
    return Image.fromarray(img, "RGBA")


def make_bolt(size=64):
    S = size * 4
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    core = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd, cd = ImageDraw.Draw(glow), ImageDraw.Draw(core)
    r = random.Random(11)
    cx = cy = S / 2
    for k in range(5):
        ang = k * (2 * math.pi / 5) + 0.3
        pts = [(cx, cy)]
        L = S * 0.46
        steps = 7
        for s in range(1, steps + 1):
            f = s / steps
            off = r.uniform(-S * 0.045, S * 0.045)
            pts.append((cx + math.cos(ang) * L * f + math.cos(ang + 1.57) * off,
                        cy + math.sin(ang) * L * f + math.sin(ang + 1.57) * off))
        gd.line(pts, fill=(90, 170, 255, 190), width=int(S * 0.045))
        cd.line(pts, fill=(235, 248, 255, 255), width=int(S * 0.014))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.012))
    out = Image.alpha_composite(glow, core)
    return out.resize((size, size), Image.LANCZOS)


def make_rune(size=128):
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = S / 2
    col = (225, 195, 255, 235)
    soft = (150, 90, 255, 150)
    d.ellipse([c - c * .98, c - c * .98, c + c * .98, c + c * .98], fill=(120, 60, 220, 38))
    for rad, w, cc in ((.97, 6, col), (.90, 3, soft), (.62, 5, col), (.56, 3, soft)):
        d.ellipse([c - c * rad, c - c * rad, c + c * rad, c + c * rad], outline=cc, width=w * 2)
    for i in range(72):  # tick marks
        a = i * math.pi / 36
        r1, r2 = c * .90, c * (.94 if i % 6 else .965)
        d.line([(c + math.cos(a) * r1, c + math.sin(a) * r1), (c + math.cos(a) * r2, c + math.sin(a) * r2)],
               fill=col, width=5)
    r = random.Random(5)
    for i in range(36):  # glyph ring between the two outer circles
        a = i * math.pi / 18
        rr = c * .78
        gx, gy = c + math.cos(a) * rr, c + math.sin(a) * rr
        tx, ty = -math.sin(a), math.cos(a)
        nx, ny = math.cos(a), math.sin(a)
        for _ in range(r.randint(2, 3)):
            p1 = (gx + tx * r.uniform(-14, 14) + nx * r.uniform(-18, 18), gy + ty * r.uniform(-14, 14) + ny * r.uniform(-18, 18))
            p2 = (gx + tx * r.uniform(-14, 14) + nx * r.uniform(-18, 18), gy + ty * r.uniform(-14, 14) + ny * r.uniform(-18, 18))
            d.line([p1, p2], fill=col, width=5)
    for k in range(2):  # hexagram
        pts = [(c + math.cos(k * math.pi / 3 + j * 2 * math.pi / 3 - math.pi / 2) * c * .56,
                c + math.sin(k * math.pi / 3 + j * 2 * math.pi / 3 - math.pi / 2) * c * .56) for j in range(3)]
        d.polygon(pts, outline=col)
        d.line(pts + [pts[0]], fill=col, width=6)
    d.ellipse([c - 22, c - 22, c + 22, c + 22], outline=col, width=6)
    glow = img.filter(ImageFilter.GaussianBlur(3))
    img = Image.alpha_composite(glow, img)
    return img.resize((size, size), Image.LANCZOS)


def make_crystal(size=32):
    yy, xx = np.mgrid[0:size, 0:size]
    img = np.zeros((size, size, 4), dtype="uint8")
    f = (xx + yy) / (2 * size)
    img[:, :, 0] = 230 + 25 * f
    img[:, :, 1] = 110 + 100 * f
    img[:, :, 2] = 240 + 15 * f
    img[:, :, 3] = 255
    img[::8, :, :3] = 255
    img[:, ::8, :3] = 255
    return Image.fromarray(img, "RGBA")


# ------------------------------------------------------------------ model spec
def cube(c, s, r=(0, 0, 0), m="dark"):
    return {"c": list(c), "s": list(s), "r": list(r), "m": m}


def head_cubes(ox, oy, oz):
    head = [
        cube((ox, oy, oz), (2.4, 1.8, 2.6), m="dark"),
        cube((ox, oy + .95, oz - .2), (1.6, .3, 2.2), m="plate"),
        cube((ox, oy - .2, oz + 2.0), (1.6, 1.0, 2.0), m="dark2"),
        cube((ox - .45, oy + .2, oz + 3.02), (.3, .3, .12), m="spike"),
        cube((ox + .45, oy + .2, oz + 3.02), (.3, .3, .12), m="spike"),
        cube((ox - 1.22, oy + .4, oz + .5), (.16, .36, .8), m="eye"),
        cube((ox + 1.22, oy + .4, oz + .5), (.16, .36, .8), m="eye"),
        cube((ox - .95, oy + 1.05, oz - 1.0), (.5, .5, 2.0), (28, 0, 0), "spike"),
        cube((ox + .95, oy + 1.05, oz - 1.0), (.5, .5, 2.0), (28, 0, 0), "spike"),
        cube((ox, oy + 1.2, oz - .2), (.6, .6, 1.6), (10, 0, 0), "spike"),
        cube((ox, oy + 1.15, oz - 1.3), (.5, .5, 1.2), (22, 0, 0), "spike"),
    ]
    jaw = [
        cube((ox, oy - .85, oz + 1.1), (1.8, .5, 3.2), m="dark"),
        cube((ox - .65, oy - .5, oz + 2.3), (.2, .4, .2), m="spike"),
        cube((ox + .65, oy - .5, oz + 2.3), (.2, .4, .2), m="spike"),
    ]
    return head, jaw


def sine(pivot, axis, bias, amp, speed, phase, parent=None):
    return {"pivot": list(pivot), "axis": list(axis), "parent": parent,
            "bias": bias, "amp": amp, "speed": speed, "phase": phase}


def build_dragon():
    parts, joints = [], {}
    body = [
        cube((0, 0, 0), (5, 3.2, 7)),
        cube((0, 1.75, 0), (3.2, .5, 6), m="plate"),
        cube((0, -1.7, 0), (3.6, .5, 6), m="dark2"),
    ]
    for z in (2.6, 1.0, -.6, -2.2):
        body.append(cube((0, 2.4, z), (.6, 1.2, .6), (15, 0, 0), "spike"))
    body += [
        cube((0, .3, 4.6), (2.2, 2.2, 3)), cube((0, 0, 6.3), (1.9, 1.9, 2), m="dark2"),
        cube((0, 1.5, 4.4), (.5, 1.0, .5), (20, 0, 0), "spike"), cube((0, 1.2, 6.0), (.5, .9, .5), (20, 0, 0), "spike"),
    ]
    for s in (-1, 1):
        body += [
            cube((s * 2.4, .2, 4.1), (1.8, 1.8, 3), (0, s * 22, 0)),
            cube((s * 3.6, -.3, 5.8), (1.6, 1.6, 2.4), (0, s * 30, 0), "dark2"),
            cube((s * 2.4, 1.3, 4.0), (.45, 1.0, .45), (20, 0, 0), "spike"),
            cube((s * 3.6, .8, 5.6), (.45, .9, .45), (20, 0, 0), "spike"),
        ]
    parts.append({"name": "body", "cubes": body, "joint": None})

    for i, (ox, oy, oz, ph) in enumerate([(0, -.5, 8.2, 0), (-4.9, -.9, 7.6, -1.7), (4.9, -.9, 7.6, 1.7)]):
        h, j = head_cubes(ox, oy, oz)
        joints[f"sway{i}"] = sine((ox, oy, oz - 2.8), (0, 1, 0), 0, .14, .06, ph)
        joints[f"jaw{i}"] = sine((ox, oy - .7, oz - 1.0), (1, 0, 0), .16, .14, .1, ph, f"sway{i}")
        parts.append({"name": f"head{i}", "cubes": h, "joint": f"sway{i}"})
        parts.append({"name": f"jaw{i}", "cubes": j, "joint": f"jaw{i}"})

    for s in (-1, 1):
        tag = "L" if s > 0 else "R"
        joints[f"flap{tag}"] = sine((s * 2.4, 1.2, .5), (0, 0, 1), s * .16, s * .38, .11, 0)
        joints[f"flex{tag}"] = sine((s * 8.3, 1.3, .5), (0, 0, 1), 0, s * .35, .11, -.9, f"flap{tag}")
        a1 = [cube((s * 5.2, 1.3, .6), (6, .8, .9)), cube((s * 5.2, 1.15, -2.2), (6, .14, 4.6), m="membrane")]
        for i in range(3):
            a1.append(cube((s * (3.4 + i * 1.9), 1.25, -5.2), (.45, .5, 2.2), (0, s * -12, 0), "spike"))
        a2 = [cube((s * 11.0, 1.2, .2), (5.4, .6, .8)), cube((s * 11.0, 1.05, -2.0), (5.4, .12, 4.0), m="membrane")]
        for i in range(3):
            a2.append(cube((s * (9.8 + i * 1.9), 1.15, -4.7), (.4, .45, 2.0), (0, s * -12, 0), "spike"))
        a2.append(cube((s * 14.0, 1.3, 1.0), (.5, .5, 2.0), (0, s * 20, 0), "spike"))
        parts.append({"name": f"wing1{tag}", "cubes": a1, "joint": f"flap{tag}"})
        parts.append({"name": f"wing2{tag}", "cubes": a2, "joint": f"flex{tag}"})

    for i in range(8):
        n = i + 1
        z, y, sz = -4.2 - i * 1.55, .4 + i * .5, 1.9 - .17 * i
        joints[f"tail{i}"] = sine((0, .6, -3.5), (0, 1, 0), 0, .05 * n, .07, 0)
        cs = [cube((0, y, z), (sz, sz, 2.0), (18, 0, 0), "dark" if i % 2 == 0 else "dark2"),
              cube((0, y + sz / 2 + .3, z), (.45, .9, .45), (18, 0, 0), "spike")]
        if i == 7:
            cs.append(cube((0, y + .9, z - 1.2), (.7, .7, 2.4), (40, 0, 0), "spike"))
        parts.append({"name": f"tail{i}", "cubes": cs, "joint": f"tail{i}"})
    return parts, joints


def build_dragonling():
    parts, joints = [], {}
    body = [cube((0, 0, 0), (1.1, .9, 1.8)), cube((0, .55, 0), (.7, .2, 1.4), m="plate"),
            cube((0, .8, .3), (.2, .4, .2), (15, 0, 0), "spike"), cube((0, .8, -.3), (.2, .4, .2), (15, 0, 0), "spike")]
    parts.append({"name": "body", "cubes": body, "joint": None})
    joints["headj"] = sine((0, .2, .9), (0, 1, 0), 0, .2, .09, 0)
    head = [cube((0, .25, 1.45), (.9, .75, .9)), cube((0, .1, 2.1), (.6, .4, .8), m="dark2"),
            cube((-.47, .35, 1.6), (.08, .16, .3), m="eye"), cube((.47, .35, 1.6), (.08, .16, .3), m="eye"),
            cube((-.3, .7, 1.2), (.2, .2, .8), (28, 0, 0), "spike"), cube((.3, .7, 1.2), (.2, .2, .8), (28, 0, 0), "spike")]
    parts.append({"name": "head", "cubes": head, "joint": "headj"})
    for s in (-1, 1):
        tag = "L" if s > 0 else "R"
        joints[f"wing{tag}"] = sine((s * .5, .4, 0), (0, 0, 1), s * .2, s * .7, .35, 0)
        parts.append({"name": f"wing{tag}", "joint": f"wing{tag}", "cubes": [
            cube((s * 1.4, .45, .1), (1.8, .2, .3)), cube((s * 1.4, .4, -.5), (1.8, .05, 1.2), m="membrane"),
            cube((s * 2.1, .45, -1.1), (.15, .15, .7), (0, s * -12, 0), "spike")]})
    for i in range(4):
        n = i + 1
        s_ = .7 - .1 * i
        joints[f"t{i}"] = sine((0, 0, -.9), (0, 1, 0), 0, .12 * n, .15, 0)
        parts.append({"name": f"tail{i}", "joint": f"t{i}", "cubes": [
            cube((0, .05 + i * .1, -1.3 - i * .6), (s_, s_, .8), m="dark" if i % 2 == 0 else "dark2"),
            cube((0, .35 + i * .1, -1.3 - i * .6), (.15, .3, .15), m="spike")]})
    return parts, joints


def shrink(cubes, f, o):
    """scale cubes (position + size) by f around origin o"""
    out = []
    for c in cubes:
        out.append({"c": [o[a] + (c["c"][a] - o[a]) * f for a in range(3)],
                    "s": [v * f for v in c["s"]], "r": list(c["r"]), "m": c["m"]})
    return out


def build_sideheads():
    """Two extra dragon heads (left/right) on arched necks. Coordinates are dragon-local:
    +Z = forward (the way the vanilla head points), +X = left, 1 unit = 1 block.
    The vanilla dragon keeps its own (middle) head, so together it has THREE."""
    parts, joints = [], {}
    HEAD_F = 0.72
    for s in (1, -1):
        tag = "L" if s > 0 else "R"
        start = (s * 1.3, 0.7, 2.6)
        end = (s * 4.6, 0.5, 7.2)
        joints[f"sway{tag}"] = sine(start, (0, 1, 0), 0, .10, .06, 0 if s > 0 else 1.7)
        # arched neck: 6 chunky segments following a curve
        N = 6
        pts = []
        for i in range(N + 1):
            t = i / N
            pts.append((start[0] + (end[0] - start[0]) * t ** 1.3,
                        start[1] + (end[1] - start[1]) * t + 0.9 * math.sin(math.pi * t),
                        start[2] + (end[2] - start[2]) * t))
        neck = []
        for i in range(N):
            a, b = pts[i], pts[i + 1]
            dx, dy, dz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
            ln = math.sqrt(dx * dx + dy * dy + dz * dz)
            yaw = math.degrees(math.atan2(dx, dz))
            pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
            th = 1.6 - 0.07 * i
            mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)
            rot = (pitch, yaw, 0)
            neck.append(cube(mid, (th, th, ln + 0.5), rot, "dark" if i % 2 == 0 else "dark2"))
            neck.append(cube((mid[0], mid[1] + th / 2 + .25, mid[2]), (.4, .7, .4), (0, 0, 0), "spike"))
        parts.append({"name": f"neck{tag}", "cubes": neck, "joint": f"sway{tag}"})
        ox, oy, oz = end[0], end[1] + 0.1, end[2] + 0.9
        h, j = head_cubes(ox, oy, oz)
        h = shrink(h, HEAD_F, (ox, oy, oz))
        j = shrink(j, HEAD_F, (ox, oy, oz))
        joints[f"jaw{tag}"] = sine((ox, oy - .5, oz - .7), (1, 0, 0), .16, .14, .1, 0 if s > 0 else 1.7, f"sway{tag}")
        parts.append({"name": f"head{tag}", "cubes": h, "joint": f"sway{tag}"})
        parts.append({"name": f"jaw{tag}", "cubes": j, "joint": f"jaw{tag}"})
    return parts, joints



# ------------------------------------------------------------------ vanilla-exact side heads
# The two extra heads are 1:1 copies of the vanilla ender dragon head (same boxes, same UVs, same texture),
# on necks made of the vanilla neck segment. Vanilla model space is y-down / head toward -z; the item models
# here are y-up / head toward +z (a plain 180 degree turn about X), so no texture gets mirrored.
VTEX = "dragon_vanilla"          # = dragon texture + dragon_eyes overlay, copied into the pack


def vbox(u0, v0, x0, y0, z0, w, h, d):
    """vanilla model box (px, y-down, head toward -z) -> item-model element (px, y-up, head toward +z) with the
    exact vanilla UV layout of ModelPart.Cube on a 256x256 texture"""
    def uv(a, b, c, e):
        return {"uv": [a / 16.0, b / 16.0, c / 16.0, e / 16.0], "texture": "#t"}
    faces = {
        "up":    uv(u0 + d, v0, u0 + d + w, v0 + d),
        "down":  uv(u0 + d + w, v0, u0 + d + 2 * w, v0 + d),
        "west":  uv(u0, v0 + d, u0 + d, v0 + d + h),
        "south": uv(u0 + d, v0 + d, u0 + d + w, v0 + d + h),      # vanilla front (-z) -> +z
        "east":  uv(u0 + d + w, v0 + d, u0 + 2 * d + w, v0 + d + h),
        "north": uv(u0 + 2 * d + w, v0 + d, u0 + 2 * d + 2 * w, v0 + d + h),
    }
    return {"lo": [x0, -(y0 + h), -(z0 + d)], "hi": [x0 + w, -y0, -z0], "faces": faces}


def emit_part_model(JA, name, boxes, k=1.0):
    """write item model <name>; returns the centre (blocks, in the part's own frame) the plugin must use"""
    lo = [min(b["lo"][a] for b in boxes) for a in range(3)]
    hi = [max(b["hi"][a] for b in boxes) for a in range(3)]
    ctr = [(lo[a] + hi[a]) / 2 for a in range(3)]
    f = 1.0 / k
    els = []
    for b in boxes:
        els.append({"from": [round(8 + (b["lo"][a] - ctr[a]) * f, 4) for a in range(3)],
                    "to": [round(8 + (b["hi"][a] - ctr[a]) * f, 4) for a in range(3)],
                    "faces": b["faces"]})
    write(f"{JA}/models/item/{name}.json", {"textures": {"t": f"{NS}:item/{VTEX}", "particle": f"{NS}:item/{VTEX}"},
                                            "elements": els})
    return [c / 16.0 for c in ctr]


def emit_sideheads(JA, overrides):
    S = 16.0
    # --- vanilla head: upperlip, upperhead, 2 horn scales, 2 nostrils  (EnderDragonModel "head")
    head_boxes = [
        vbox(176, 44, -6, -1, -24, 12, 5, 16),   # upperlip
        vbox(112, 30, -8, -8, -10, 16, 16, 16),  # upperhead
        vbox(0, 0, -5, -12, -4, 2, 4, 6),        # scale (left horn)
        vbox(112, 0, -5, -3, -22, 2, 2, 4),      # nostril
        vbox(0, 0, 3, -12, -4, 2, 4, 6),         # scale (right horn)
        vbox(112, 0, 3, -3, -22, 2, 2, 4),       # nostril
    ]
    # --- vanilla jaw: pivot (0,4,-8), box (-6,0,-16, 12x4x16)  -> absolute (-6,4,-24)
    jaw_boxes = [vbox(176, 65, -6, 4, -24, 12, 4, 16)]
    # --- vanilla neck segment: 10x10x10 + spine
    neck_boxes = [vbox(192, 104, -5, -5, -5, 10, 10, 10), vbox(48, 0, -1, -9, -3, 2, 4, 6)]
    hc = emit_part_model(JA, "sideheads_head", head_boxes)
    jc = emit_part_model(JA, "sideheads_jaw", jaw_boxes)
    nc = emit_part_model(JA, "sideheads_neck", neck_boxes)
    CMD_NECK, CMD_HEAD, CMD_JAW = 5001, 5002, 5003
    for c, m in ((CMD_NECK, "sideheads_neck"), (CMD_HEAD, "sideheads_head"), (CMD_JAW, "sideheads_jaw")):
        overrides.append((c, f"{NS}:item/{m}"))

    # The plugin places these procedurally (ModelInstance.updateTriple) with the vanilla dragon's own neck/head maths.
    # Only the part list matters: per side head 8 neck pieces, 1 head, 1 jaw (20 item displays in total).
    parts, joints = [], {}
    for _side in range(2):
        for _ in range(8):
            parts.append({"id": "sideheads_neck", "cmd": CMD_NECK, "center": [0, 0, 0], "k": 1.0, "joint": None})
        parts.append({"id": "sideheads_head", "cmd": CMD_HEAD, "center": [0, 0, 0], "k": 1.0, "joint": None})
        parts.append({"id": "sideheads_jaw", "cmd": CMD_JAW, "center": [0, 0, 0], "k": 1.0, "joint": None})
    return parts, joints


def build_head():
    h, j = head_cubes(0, 0, 0)
    return [{"name": "head", "cubes": h + j, "joint": None}], {}


def texfuncs():
    base = {"dark": tex_dark, "dark2": tex_dark2, "plate": tex_plate, "membrane": tex_membrane, "spike": tex_spike, "eye": tex_eye}
    if DRAGON_TEX:
        base.update(sampled_tiles())
    return [base[m] for m in MATS]


FAMILIES = {
    "dragon": dict(build=build_dragon, cmd=1000, scale=.85, lift=2.0, bedrock="ender_dragon"),
    "dragonling": dict(build=build_dragonling, cmd=2000, scale=1.0, lift=.6, bedrock="endermite"),
    "head": dict(build=build_head, cmd=3000, scale=.22, lift=.15, bedrock="wither_skull"),
    "sideheads": dict(build=build_sideheads, cmd=5000, scale=1.0, lift=1.5, bedrock=None, java_only=True),
}

# ------------------------------------------------------------------ helpers
def bbox(cubes):
    lo = [1e9] * 3; hi = [-1e9] * 3
    for c in cubes:
        for a in range(3):
            e = max(c["s"]) * .55 if any(c["r"]) else c["s"][a] / 2
            lo[a] = min(lo[a], c["c"][a] - e); hi[a] = max(hi[a], c["c"][a] + e)
    return lo, hi


def quant(r):
    pitch, yaw, roll = r
    cands = [("x", pitch), ("y", yaw), ("z", roll)]
    axis, ang = max(cands, key=lambda t: abs(t[1]))
    ang = max(-45, min(45, round(ang / 22.5) * 22.5))
    return (axis, ang) if abs(ang) >= 22.5 else None


FACES = ["north", "east", "south", "west", "up", "down"]


def java_elements(cubes, center, k, tex_key=lambda m: m):
    els = []
    f = 16.0 / k
    for c in cubes:
        lo = [8 + (c["c"][a] - c["s"][a] / 2 - center[a]) * f for a in range(3)]
        hi = [8 + (c["c"][a] + c["s"][a] / 2 - center[a]) * f for a in range(3)]
        el = {"from": [round(v, 4) for v in lo], "to": [round(v, 4) for v in hi],
              "faces": {fc: {"uv": [0, 0, 16, 16], "texture": "#" + tex_key(c["m"])} for fc in FACES}}
        q = quant(c["r"])
        if q:
            el["rotation"] = {"angle": q[1], "axis": q[0],
                              "origin": [round(8 + (c["c"][a] - center[a]) * f, 4) for a in range(3)]}
        els.append(el)
    return els


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(data, fh, indent=1)


# ------------------------------------------------------------------ build
def main():
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    java = os.path.join(OUT, "java")
    JA = os.path.join(java, "assets", NS)
    # ---- textures
    for name, fn in zip(MATS, texfuncs()):
        save_rgb(fn(), f"{JA}/textures/item/{name}.png")
    if DRAGON_TEX:
        base = Image.open(DRAGON_TEX).convert("RGBA")
        eyes = os.path.join(os.path.dirname(DRAGON_TEX), "dragon_eyes.png")
        if os.path.exists(eyes):
            base = Image.alpha_composite(base, Image.open(eyes).convert("RGBA").resize(base.size))
        base.save(f"{JA}/textures/item/{VTEX}.png")
    make_ring().save(f"{JA}/textures/item/ring.png")
    make_bolt().save(f"{JA}/textures/item/bolt.png")
    make_rune().save(f"{JA}/textures/item/rune.png")

    plugin = {"families": {}}
    overrides = []  # (cmd, model id)

    for fam, F in list(FAMILIES.items()):
        if SKIP_DRAGON and fam == "dragon":
            del FAMILIES[fam]
            continue
        if fam == "sideheads":
            sp, sj = emit_sideheads(JA, overrides)
            plugin["families"][fam] = {"parts": sp, "joints": sj}
            F["_parts"], F["_joints"] = [], {}
            continue
        parts, joints = F["build"]()
        pj = []
        for i, p in enumerate(parts):
            lo, hi = bbox(p["cubes"])
            center = [(lo[a] + hi[a]) / 2 for a in range(3)]
            ext = max(max(abs(lo[a] - center[a]), abs(hi[a] - center[a])) for a in range(3))
            k = max(0.5, math.ceil(ext * 16 / 22 * 2) / 2)
            cmd = F["cmd"] + i + 1
            mid = f"{fam}_{p['name']}"
            used = sorted({c["m"] for c in p["cubes"]})
            write(f"{JA}/models/item/{mid}.json", {
                "textures": {m: f"{NS}:item/{m}" for m in used},
                "elements": java_elements(p["cubes"], center, k)})
            overrides.append((cmd, f"{NS}:item/{mid}"))
            pj.append({"id": mid, "cmd": cmd, "center": [round(v, 4) for v in center], "k": k, "joint": p["joint"]})
        plugin["families"][fam] = {"parts": pj, "joints": joints}
        F["_parts"], F["_joints"] = parts, joints

    # ---- crystal bubble + rune circle (Java)
    R = 2.4
    k_b = 2.0
    Rp = R * 16 / k_b
    els = []
    N = 8
    for j in range(-N, N + 1):
        phi = j * (math.pi / 2 * 0.92) / N
        h, r = Rp * math.sin(phi), Rp * math.cos(phi)
        face = lambda a, b: {a: {"uv": [0, 0, 16, 16], "texture": "#ring"}, b: {"uv": [0, 0, 16, 16], "texture": "#ring"}}
        els.append({"from": [8 - r, 8 + h, 8 - r], "to": [8 + r, 8 + h, 8 + r], "faces": face("up", "down")})
        els.append({"from": [8 + h, 8 - r, 8 - r], "to": [8 + h, 8 + r, 8 + r], "faces": face("east", "west")})
        els.append({"from": [8 - r, 8 - r, 8 + h], "to": [8 + r, 8 + r, 8 + h], "faces": face("north", "south")})
    for ang in (45, -45, 0):
        b = Rp * .98
        els.append({"from": [8 - b, 8 - b, 8], "to": [8 + b, 8 + b, 8],
                    "rotation": {"angle": ang, "axis": "y", "origin": [8, 8, 8]},
                    "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "#bolt"},
                              "south": {"uv": [0, 0, 16, 16], "texture": "#bolt"}}})
    for e in els:
        e["shade"] = False
    write(f"{JA}/models/item/crystal_bubble.json", {"textures": {"ring": f"{NS}:item/ring", "bolt": f"{NS}:item/bolt"}, "elements": els})
    overrides.append((4001, f"{NS}:item/crystal_bubble"))
    k_r = 3.0
    half = 3.0 * 16 / k_r
    write(f"{JA}/models/item/crystal_rune.json", {"textures": {"rune": f"{NS}:item/rune"}, "elements": [
        {"from": [8 - half, 8, 8 - half], "to": [8 + half, 8, 8 + half], "shade": False,
         "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "#rune"}, "down": {"uv": [0, 0, 16, 16], "texture": "#rune"}}}]})
    overrides.append((4002, f"{NS}:item/crystal_rune"))
    plugin["bubble"] = {"cmd": 4001, "k": k_b, "radius": R}
    plugin["rune"] = {"cmd": 4002, "k": k_r, "half": 3.0}

    # ---- paper item hooks (1.20 .. 1.21.3 overrides; 1.21.4+ item definition)
    overrides.sort()
    write(f"{java}/assets/minecraft/models/item/paper.json", {
        "parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/paper"},
        "overrides": [{"predicate": {"custom_model_data": c}, "model": m} for c, m in overrides]})
    write(f"{java}/assets/minecraft/items/paper.json", {"model": {
        "type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
        "entries": [{"threshold": c, "model": {"type": "minecraft:model", "model": m}} for c, m in overrides],
        "fallback": {"type": "minecraft:model", "model": "minecraft:item/paper"}}})

    # ---- blank the vanilla dragon / endermite so only the models show (Java can't swap those meshes)
    blank = lambda w, h: Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for p, (w, h) in {
        **({} if SKIP_DRAGON else {
        "textures/entity/enderdragon/dragon.png": (256, 256),
        "textures/entity/enderdragon/dragon_eyes.png": (256, 256),
        "textures/entity/enderdragon/dragon_exploding.png": (256, 256)}),
        "textures/entity/endermite.png": (64, 32),
        "textures/entity/endermite/endermite.png": (64, 32),
    }.items():
        full = os.path.join(java, "assets", "minecraft", p)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        blank(w, h).save(full)

    icon = Image.new("RGBA", (64, 64), (20, 14, 30, 255))
    ImageDraw.Draw(icon).polygon([(8, 44), (32, 8), (56, 44), (32, 36)], fill=(170, 90, 240, 255))
    icon.save(os.path.join(java, "pack.png"))
    write(os.path.join(java, "pack.mcmeta"), {"pack": {
        "pack_format": 15, "min_format": 15, "max_format": 999,
        "supported_formats": {"min_inclusive": 15, "max_inclusive": 999},
        "description": "Impossible Dragon - 3-headed dragon, dragonlings, dragon-head skulls, crystal shield"}})

    # ------------------------------------------------------------------ Bedrock
    for flip in (False, True):
        bed = os.path.join(OUT, "bedrock_flipped" if flip else "bedrock")
        build_bedrock(bed, flip, icon)
    if PLUGIN_RES:
        os.makedirs(PLUGIN_RES, exist_ok=True)
        with open(os.path.join(PLUGIN_RES, "models.json"), "w") as fh:
            json.dump(plugin, fh)
    # zips
    def zipdir(src, dst, root_files=True):
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
            for r, _, fs in os.walk(src):
                for f in fs:
                    full = os.path.join(r, f)
                    z.write(full, os.path.relpath(full, src))
    os.makedirs(os.path.join(OUT, "dist"), exist_ok=True)
    zipdir(java, os.path.join(OUT, "dist", "ImpossibleDragon-Java-ResourcePack.zip"))
    zipdir(os.path.join(OUT, "bedrock"), os.path.join(OUT, "dist", "ImpossibleDragon-Bedrock.mcpack"))
    zipdir(os.path.join(OUT, "bedrock_flipped"), os.path.join(OUT, "dist", "ImpossibleDragon-Bedrock-FLIPPED.mcpack"))


# ------------------------------------------------------------------ Bedrock writer
def build_bedrock(root, flip, icon):
    tex_dir = f"{root}/textures/entity/{NS}"
    os.makedirs(tex_dir, exist_ok=True)
    # atlas for the three mob models: 6 material tiles
    atlas = Image.new("RGBA", (TILE * len(MATS), TILE), (0, 0, 0, 0))
    for i, (name, fn) in enumerate(zip(MATS, texfuncs())):
        atlas.paste(Image.fromarray(np.clip(fn(), 0, 255).astype("uint8"), "RGB").convert("RGBA"), (i * TILE, 0))
    atlas.save(f"{tex_dir}/mobs.png")
    if DRAGON_TEX:
        ed = f"{root}/textures/entity/enderdragon"
        os.makedirs(ed, exist_ok=True)
        shutil.copy(DRAGON_TEX, f"{ed}/dragon.png")
        eyes = os.path.join(os.path.dirname(DRAGON_TEX), "dragon_eyes.png")
        if os.path.exists(eyes):
            shutil.copy(eyes, f"{ed}/dragon_eyes.png")
    # atlas for the crystal
    cat = Image.new("RGBA", (256, 192), (0, 0, 0, 0))
    cat.paste(make_crystal(), (0, 0)); cat.paste(make_ring(), (64, 0))
    cat.paste(make_bolt(), (128, 0)); cat.paste(make_rune(), (0, 64))
    cat.save(f"{tex_dir}/crystal.png")

    sx = 1 if flip else -1  # default: head toward -Z (Bedrock front); flipped variant keeps +Z

    def rot_sign(axis_idx):
        return (-1 if (not flip and axis_idx in (0, 2)) else 1)

    for fam, F in FAMILIES.items():
        if F.get("java_only"):
            continue  # Java-only (ItemDisplay) model, nothing to write for Bedrock
        parts, joints = F["_parts"], F["_joints"]
        U = 16 * F["scale"]
        lift = F["lift"]
        bones = {"root": {"name": "root", "pivot": [0, lift * 16, 0], "cubes": []}}
        for jn, j in joints.items():
            bones[jn] = {"name": jn, "pivot": conv(j["pivot"], U, lift, F["scale"], sx), "cubes": []}
            if j["parent"]:
                bones[jn]["parent"] = j["parent"]
        for jn in joints:  # parents need to exist; unparented joints hang off root
            if "parent" not in bones[jn]:
                bones[jn]["parent"] = "root"
        for p in parts:
            tgt = bones[p["joint"] or "root"]
            for c in p["cubes"]:
                ctr = conv(c["c"], U, lift, F["scale"], sx)
                size = [max(abs(v) * U, 0.02) for v in c["s"]]
                origin = [ctr[a] - size[a] / 2 for a in range(3)]
                mi = MATS.index(c["m"])
                uv = {f: {"uv": [mi * TILE, 0], "uv_size": [TILE, TILE]} for f in FACES}
                cb = {"origin": [round(v, 4) for v in origin], "size": [round(v, 4) for v in size], "uv": uv}
                if any(c["r"]):
                    q = quant(c["r"])
                    if q:
                        r = [0, 0, 0]
                        idx = "xyz".index(q[0])
                        r[idx] = q[1] * rot_sign(idx)
                        cb["pivot"] = [round(v, 4) for v in ctr]
                        cb["rotation"] = r
                tgt["cubes"].append(cb)
        geo = {"format_version": "1.12.0", "minecraft:geometry": [{
            "description": {"identifier": f"geometry.{NS}.{fam}", "texture_width": TILE * len(MATS), "texture_height": TILE,
                            "visible_bounds_width": 80, "visible_bounds_height": 40, "visible_bounds_offset": [0, 2, 0]},
            "bones": list(bones.values())}]}
        write(f"{root}/models/entity/{NS}_{fam}.geo.json", geo)
        anim_bones = {}
        for jn, j in joints.items():
            idx = [i for i, v in enumerate(j["axis"]) if v][0]
            sgn = rot_sign(idx)
            bias, amp = math.degrees(j["bias"]) * sgn, math.degrees(j["amp"]) * sgn
            expr = f"{bias:.3f} + {amp:.3f} * math.sin(({j['speed'] * 20:.4f} * query.life_time + {j['phase']:.3f}) * 57.2958)"
            rot = ["0", "0", "0"]
            rot[idx] = expr
            anim_bones[jn] = {"rotation": rot}
        write(f"{root}/animations/{NS}_{fam}.animation.json", {"format_version": "1.8.0", "animations": {
            f"animation.{NS}.{fam}": {"loop": True, "animation_length": 10, "bones": anim_bones}}})
        ids = [f"minecraft:{F['bedrock']}"] + (["minecraft:wither_skull_dangerous"] if fam == "head" else [])
        for ident in ids:
            write(f"{root}/entity/{ident.split(':')[1]}.entity.json", {"format_version": "1.10.0", "minecraft:client_entity": {
                "description": {"identifier": ident, "materials": {"default": "entity_alphatest"},
                                "textures": {"default": f"textures/entity/{NS}/mobs"},
                                "geometry": {"default": f"geometry.{NS}.{fam}"},
                                "animations": {"idle": f"animation.{NS}.{fam}"},
                                "scripts": {"animate": ["idle"]},
                                "render_controllers": ["controller.render.default"]}}})

    # ---- crystal: crystal cubes + bubble + bolts + rune circle (alpha-blended)
    P = 16
    cubes_core, cubes_bubble, cubes_bolt, cubes_rune = [], [], [], []

    def uvf(x, y, w, h):
        return {f: {"uv": [x, y], "uv_size": [w, h]} for f in FACES}
    for size, y0 in ((10, 11), (7, 12.5), (4, 14)):
        cubes_core.append({"origin": [-size / 2, y0 + (10 - size) / 2 - (10 - size) / 2 + 0, -size / 2], "size": [size] * 3,
                           "uv": uvf(0, 0, 32, 32)})
    # re-centre the three cubes on y=16
    for c, size in zip(cubes_core, (10, 7, 4)):
        c["origin"] = [-size / 2, 16 - size / 2, -size / 2]
    Rpx = 2.4 * 16
    Nn = 8
    for j in range(-Nn, Nn + 1):
        phi = j * (math.pi / 2 * .92) / Nn
        h, r = Rpx * math.sin(phi), Rpx * math.cos(phi)
        t = 0.02
        cubes_bubble.append({"origin": [-r, 16 + h, -r], "size": [2 * r, t, 2 * r], "uv": uvf(64, 0, 64, 64)})
        cubes_bubble.append({"origin": [h, 16 - r, -r], "size": [t, 2 * r, 2 * r], "uv": uvf(64, 0, 64, 64)})
        cubes_bubble.append({"origin": [-r, 16 - r, h], "size": [2 * r, 2 * r, t], "uv": uvf(64, 0, 64, 64)})
    b = Rpx * .98
    cubes_bolt.append({"origin": [-b, 16 - b, -0.01], "size": [2 * b, 2 * b, .02], "uv": uvf(128, 0, 64, 64)})
    cubes_bolt.append({"origin": [-0.01, 16 - b, -b], "size": [.02, 2 * b, 2 * b], "uv": uvf(128, 0, 64, 64)})
    cubes_rune.append({"origin": [-48, .3, -48], "size": [96, .02, 96], "uv": uvf(0, 64, 128, 128)})
    geo = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.{NS}.crystal", "texture_width": 256, "texture_height": 192,
                        "visible_bounds_width": 10, "visible_bounds_height": 8, "visible_bounds_offset": [0, 1, 0]},
        "bones": [{"name": "root", "pivot": [0, 0, 0]},
                  {"name": "core", "parent": "root", "pivot": [0, 16, 0], "cubes": cubes_core},
                  {"name": "bubble", "parent": "root", "pivot": [0, 16, 0], "cubes": cubes_bubble},
                  {"name": "bolts", "parent": "root", "pivot": [0, 16, 0], "cubes": cubes_bolt},
                  {"name": "rune", "parent": "root", "pivot": [0, 0, 0], "cubes": cubes_rune}]}]}
    write(f"{root}/models/entity/{NS}_crystal.geo.json", geo)
    write(f"{root}/animations/{NS}_crystal.animation.json", {"format_version": "1.8.0", "animations": {
        f"animation.{NS}.crystal": {"loop": True, "animation_length": 10, "bones": {
            "core": {"rotation": ["math.sin(query.life_time * 90) * 10", "query.life_time * 60", "25"],
                     "position": ["0", "math.sin(query.life_time * 120) * 1.5", "0"]},
            "bubble": {"rotation": ["0", "query.life_time * 12", "0"]},
            "bolts": {"rotation": ["0", "query.life_time * 80", "0"]},
            "rune": {"rotation": ["0", "query.life_time * -14", "0"]}}}}})
    write(f"{root}/entity/ender_crystal.entity.json", {"format_version": "1.10.0", "minecraft:client_entity": {
        "description": {"identifier": "minecraft:ender_crystal", "materials": {"default": "entity_alphablend"},
                        "textures": {"default": f"textures/entity/{NS}/crystal"},
                        "geometry": {"default": f"geometry.{NS}.crystal"},
                        "animations": {"idle": f"animation.{NS}.crystal"},
                        "scripts": {"animate": ["idle"]},
                        "render_controllers": ["controller.render.default"]}}})

    uid = lambda s: __import__("uuid").uuid5(__import__("uuid").NAMESPACE_DNS, s + ("flip" if flip else "")).__str__()
    write(f"{root}/manifest.json", {"format_version": 2, "header": {
        "name": "Impossible Dragon" + (" (flipped)" if flip else ""),
        "description": "3-headed dragon, dragonlings, dragon-head skulls, crystal shield sphere",
        "uuid": uid("impossibledragon-header"), "version": [1, 0, 0], "min_engine_version": [1, 20, 0]},
        "modules": [{"type": "resources", "uuid": uid("impossibledragon-module"), "version": [1, 0, 0]}]})
    icon.save(f"{root}/pack_icon.png")


def conv(p, U, lift, scale, sx):
    """block coords (head toward +Z, +X left) -> Bedrock pixels (default: head toward -Z)."""
    s = -1 if sx == -1 else 1
    return [s * p[0] * U, (p[1] * scale + lift) * 16, s * p[2] * U]


main()
print("ok")
