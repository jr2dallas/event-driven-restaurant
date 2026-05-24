#!/usr/bin/env python3
"""
verify-layout.py
Charge l'image de fond + le JSON de layout et affiche les éléments
avec des croix/cercles colorés pour vérifier les décalages.
Usage: python verify-layout.py [image] [layout.json]
"""
import sys, json, math
from PIL import Image, ImageDraw, ImageFont

COLORS = {
    "table":           "#c87a30",
    "chair":           "#8a5a20",
    "kitchen_station": "#ff8800",
    "bar":             "#8B4513",
    "bar_stool":       "#6b3a10",
    "entrance":        "#00cc66",
    "queue_start":     "#00aaff",
    "queue_end":       "#0066cc",
    "obstacle":        "#cc2222",
    "plant":           "#22aa44",
}

RADII = {
    "table": 30, "chair": 11, "kitchen_station": 27,
    "bar": 40, "bar_stool": 10, "entrance": 15,
    "queue_start": 12, "queue_end": 12, "obstacle": 20, "plant": 13,
}

LABELS = {
    "table":"T", "chair":"C", "kitchen_station":"K", "bar":"BAR",
    "bar_stool":"BS", "entrance":"ENT", "queue_start":"QS",
    "queue_end":"QE", "obstacle":"OBS", "plant":"PLT"
}

def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def draw_element(draw, el, type_key):
    x, y = el["x"], el["y"]
    r = el.get("r", RADII.get(type_key, 20))
    color = hex_to_rgb(COLORS.get(type_key, "#ffffff"))
    fill = color + (160,)
    outline = (255, 255, 255, 230)

    # Cercle rempli
    draw.ellipse([x-r, y-r, x+r, y+r], fill=fill, outline=outline, width=2)

    # Croix centrale
    draw.line([x-r//2, y, x+r//2, y], fill=(255,255,255,255), width=2)
    draw.line([x, y-r//2, x, y+r//2], fill=(255,255,255,255), width=2)

    # Label
    label = LABELS.get(type_key, type_key[:3].upper())
    num = el["id"].split("_")[-1] if "_" in el.get("id","") else ""
    txt = label + num
    draw.text((x, y + r + 4), txt, fill=(255, 230, 100, 255), anchor="mt")

def main():
    img_path   = sys.argv[1] if len(sys.argv) > 1 else "resto_empty.png"
    json_path  = sys.argv[2] if len(sys.argv) > 2 else "restaurant-layout.json"
    out_path   = "layout-verify.png"

    print(f"📂 Image  : {img_path}")
    print(f"📂 Layout : {json_path}")

    img = Image.open(img_path).convert("RGBA")
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    with open(json_path) as f:
        layout = json.load(f)

    total = 0
    for type_key, elements in layout.items():
        if not isinstance(elements, list):
            continue
        for el in elements:
            draw_element(draw, el, type_key)
            total += 1

    # Grille de référence (tous les 100px)
    w, h = img.size
    grid_color = (255, 255, 255, 25)
    for x in range(0, w, 100):
        draw.line([x, 0, x, h], fill=grid_color, width=1)
    for y in range(0, h, 100):
        draw.line([0, y, w, y], fill=grid_color, width=1)

    # Graduation tous les 200px
    ruler_color = (255, 230, 100, 60)
    for x in range(0, w, 8):
        draw.line([x, 0, x, h], fill=ruler_color, width=1)
        draw.text((x+3, 4), str(x), fill=(255, 230, 100, 180))
    for y in range(0, h, 8):
        draw.line([0, y, w, y], fill=ruler_color, width=1)
        draw.text((4, y+3), str(y), fill=(255, 230, 100, 180))

    result = Image.alpha_composite(img, overlay).convert("RGB")
    result.save(out_path)
    print(f"✅ Vérification sauvegardée : {out_path}")
    print(f"   {total} éléments affichés")
    print(f"   Dimensions image : {w}x{h}px")

if __name__ == "__main__":
    main()
