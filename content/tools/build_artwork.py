#!/usr/bin/env python3
"""
build_artwork.py — 把「Arkham Artwork.xlsx」(繪師/美術歸屬)轉成我們的參考格式。
以 AHDB Code(= 我們的 card code)為 key,套用 Artist Fixes 更正繪師。

輸出:
  content/reference/artwork.json           # code -> {name, artist, type, packCode, classCode, level, side, ...}
  content/reference/characters-in-art.json # 哪些角色出現在哪張卡的美術裡

用法:  python3 content/tools/build_artwork.py ["/path/to/Arkham Artwork.xlsx"]
"""
import json, re, sys
from pathlib import Path
try:
    import openpyxl
except ImportError:
    sys.exit("需要 openpyxl:python3 -m pip install openpyxl")

SRC = sys.argv[1] if len(sys.argv) > 1 else str(Path.home() / "Downloads" / "Arkham Artwork.xlsx")
OUT = Path(__file__).resolve().parents[2] / "content" / "reference"
wb = openpyxl.load_workbook(SRC, data_only=True)
rows = lambda name: list(wb[name].iter_rows(values_only=True))

# --- Artist Fixes:code -> 正確繪師 ---
fixes = {}
fr = rows("Artist Fixes")
hi = next((i for i, r in enumerate(fr) if r and any(str(c).strip() == "Card Numbers Affected" for c in r if c)), None)
if hi is not None:
    idx = {str(c).strip(): i for i, c in enumerate(fr[hi]) if c}
    for r in fr[hi + 1:]:
        cell = r[idx["Card Numbers Affected"]] if idx.get("Card Numbers Affected") is not None else None
        to = r[idx.get("Change to", 4)] if idx.get("Change to") is not None else None
        if not cell or not to:
            continue
        for cd in re.split(r"[,\s]+", str(cell).strip()):
            if cd.strip():
                fixes[cd.strip()] = str(to).strip()

# --- Art on Cards ---
ar = rows("Art on Cards")
idx = {}
for i, h in enumerate(ar[0]):
    h = str(h).strip() if h else ""
    if h and h != "None" and h not in idx:
        idx[h] = i
def g(row, key):
    i = idx.get(key)
    v = row[i] if (i is not None and i < len(row)) else None
    return None if v in (None, "") else v

art, fixed = {}, 0
for row in ar[1:]:
    code = g(row, "AHDB Code")
    if not code:
        continue
    code = str(code).strip()
    artist = g(row, "Artist")
    if code in fixes:
        artist = fixes[code]; fixed += 1
    rec = {
        "code": code, "name": g(row, "Card Name"), "artist": artist,
        "type": g(row, "Card Type"), "packCode": g(row, "pack code"),
        "classCode": g(row, "class code"), "level": g(row, "level"),
        "side": g(row, "Side"), "cycle": g(row, "Cycle"), "cycleCode": g(row, "Cycle Code"),
        "sku": g(row, "SKU"), "cardNum": (str(g(row, "Card #")) if g(row, "Card #") else None),
        "inRepo": str(g(row, "In Repo?") or "").lower() == "x",
        "artSource": g(row, "CoC Art Source"),
    }
    art[code] = {k: v for k, v in rec.items() if v not in (None, "")}

# --- Characters in Art ---
cr = rows("Characters in Art")
cidx = {str(h).strip(): i for i, h in enumerate(cr[0]) if h and str(h).strip() != "None"}
chars = []
for r in cr[1:]:
    row = {k: (r[i] if i < len(r) else None) for k, i in cidx.items()}
    row = {k: v for k, v in row.items() if v not in (None, "")}
    if row:
        chars.append(row)

OUT.mkdir(parents=True, exist_ok=True)
(OUT / "artwork.json").write_text(json.dumps(art, ensure_ascii=False, indent=1))
(OUT / "characters-in-art.json").write_text(json.dumps(chars, ensure_ascii=False, indent=1))
print(f"artwork.json:{len(art)} 張卡(套用 {fixed} 筆 Artist Fixes)")
print(f"characters-in-art.json:{len(chars)} 筆")
if art:
    print("範例欄位:", sorted(next(iter(art.values())).keys()))
