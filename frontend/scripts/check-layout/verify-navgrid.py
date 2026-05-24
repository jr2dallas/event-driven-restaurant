#!/usr/bin/env python3
"""
verify-navgrid.py
Génère une visualisation de la grille de navigation (navmesh) depuis le JSON layout.
Cases rouges = bloquées, vertes semi-transparentes = libres
Usage: python verify-navgrid.py [image] [layout.json] [tile_size=8]
"""
import sys, json, math
from PIL import Image, ImageDraw

TILE = int(sys.argv[3]) if len(sys.argv) > 3 else 8
IMG_PATH    = sys.argv[1] if len(sys.argv) > 1 else "resto_empty.png"
LAYOUT_PATH = sys.argv[2] if len(sys.argv) > 2 else "restaurant-layout.json"
OUT_PATH    = f"navgrid-verify-{TILE}px.png"

PADDING = {
    "table": 6, "chair": 4, "kitchen_station": 8,
    "bar": 8, "bar_stool": 4, "obstacle": 6,
    "plant": 4, "bar_stool": 3,
}
# Types qui ne bloquent PAS les sprites
PASSABLE = {"entrance", "queue_start", "queue_end"}

def block_circle(grid, W, H, cx, cy, r, padding=4):
    tr = math.ceil((r + padding) / TILE)
    tcx = int(cx // TILE)
    tcy = int(cy // TILE)
    for dy in range(-tr, tr + 1):
        for dx in range(-tr, tr + 1):
            if dx*dx + dy*dy <= tr*tr:
                tx, ty = tcx + dx, tcy + dy
                if 0 <= tx < W and 0 <= ty < H:
                    grid[ty][tx] = 1

def block_rect(grid, W, H, x1, y1, x2, y2, padding=4):
    tx1 = max(0, int((x1 - padding) // TILE))
    ty1 = max(0, int((y1 - padding) // TILE))
    tx2 = min(W-1, int((x2 + padding) // TILE))
    ty2 = min(H-1, int((y2 + padding) // TILE))
    for ty in range(ty1, ty2 + 1):
        for tx in range(tx1, tx2 + 1):
            grid[ty][tx] = 1

img = Image.open(IMG_PATH).convert("RGBA")
IW, IH = img.size
GW, GH = IW // TILE, IH // TILE

print(f"🖼  Image     : {IW}x{IH}px")
print(f"🔲 Tile size  : {TILE}px")
print(f"📐 Grille     : {GW}x{GH} = {GW*GH:,} cases")

grid = [[0]*GW for _ in range(GH)]

with open(LAYOUT_PATH) as f:
    layout = json.load(f)

blocked = 0
for type_key, elements in layout.items():
    if not isinstance(elements, list) or type_key in PASSABLE:
        continue
    pad = PADDING.get(type_key, 4)
    for el in elements:
        r = el.get("r", 20)
        block_circle(grid, GW, GH, el["x"], el["y"], r, padding=pad)

# Murs extérieurs (1 tile de bordure tout autour)
for tx in range(GW):
    grid[0][tx] = 1
    grid[GH-1][tx] = 1
for ty in range(GH):
    grid[ty][0] = 1
    grid[ty][GW-1] = 1

# Compter les cases bloquées
blocked = sum(grid[ty][tx] for ty in range(GH) for tx in range(GW))
free = GW * GH - blocked
print(f"🔴 Bloquées   : {blocked:,} ({blocked*100//(GW*GH)}%)")
print(f"🟢 Libres     : {free:,} ({free*100//(GW*GH)}%)")

# Rendu
overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
draw = ImageDraw.Draw(overlay)

for ty in range(GH):
    for tx in range(GW):
        x0, y0 = tx * TILE, ty * TILE
        x1, y1 = x0 + TILE - 1, y0 + TILE - 1
        if grid[ty][tx] == 1:
            draw.rectangle([x0, y0, x1, y1], fill=(220, 40, 40, 110))
        else:
            # Juste une fine grille verte pour les cases libres
            draw.rectangle([x0, y0, x1, y1], outline=(60, 200, 60, 35))

# Grille de repère tous les 10 tiles
for tx in range(0, GW, 10):
    draw.line([tx*TILE, 0, tx*TILE, IH], fill=(255, 220, 80, 50), width=1)
for ty in range(0, GH, 10):
    draw.line([0, ty*TILE, IW, ty*TILE], fill=(255, 220, 80, 50), width=1)

# Légende
draw.rectangle([10, 10, 200, 80], fill=(0,0,0,160))
draw.rectangle([18, 20, 36, 36], fill=(220, 40, 40, 200))
draw.text((42, 22), f"Bloqué ({blocked:,})", fill=(255,180,180,255))
draw.rectangle([18, 44, 36, 60], fill=(60, 200, 60, 120), outline=(60,200,60,200))
draw.text((42, 46), f"Libre ({free:,})", fill=(180,255,180,255))

result = Image.alpha_composite(img, overlay).convert("RGB")
result.save(OUT_PATH)
print(f"\n✅ Sauvegardé : {OUT_PATH}")
print(f"   Ouvre ce fichier pour vérifier les zones bloquées.")
