#!/usr/bin/env python3
"""
build_cards.py — 從 ArkhamDB 抓「官方 核心 + 主線 8 戰役」卡料,
轉成 docs/06 的 CardDefinition schema,輸出到 content/cards/generated/(gitignored)。

原則:
- 只處理 content/reference/packs.json 中 category ∈ {core, campaign} 的卡盒。
- 調查員可用 content/reference/selected-investigators.json 白名單篩選;
  空 / 無檔 → 收錄 scope 內全部調查員(之後由使用者填入 codes)。
- 卡圖不下載(玩家自帶,docs/06 §12);只寫 image 路徑字串。
- 卡牌文字為 FFG 版權 → 輸出到 gitignored 目錄,不入公開版控。

用法:  python3 content/tools/build_cards.py
"""
import json, os, re, urllib.request
from pathlib import Path

ROOT  = Path(__file__).resolve().parents[2]          # repo root
REF   = ROOT / "content" / "reference"
OUT   = ROOT / "content" / "cards" / "generated"
CACHE = Path(__file__).resolve().parent / ".cache"
API   = "https://arkhamdb.com/api/public/cards/?encounter=1"   # 全卡(玩家 + 遭遇)

def _load_artwork():
    f = ROOT / "content" / "reference" / "artwork.json"
    try:
        return json.loads(f.read_text()) if f.exists() else {}
    except Exception:
        return {}
ARTWORK = _load_artwork()   # 繪師/美術(build_artwork.py 產出);有就併進卡定義

FACTIONS = {"guardian", "seeker", "rogue", "mystic", "survivor", "neutral"}
SKILLMAP = {"skill_willpower": "WILLPOWER", "skill_intellect": "INTELLECT",
            "skill_combat": "COMBAT", "skill_agility": "AGILITY", "skill_wild": "WILD"}

def fetch_all():
    CACHE.mkdir(parents=True, exist_ok=True)
    cache = CACHE / "arkhamdb_all.json"
    if cache.exists():
        print(f"使用快取 {cache}")
        return json.loads(cache.read_text())
    print("下載 ArkhamDB 全卡…")
    with urllib.request.urlopen(API, timeout=90) as r:
        data = r.read()
    cache.write_bytes(data)
    return json.loads(data)

def scope_pack_codes():
    packs = json.loads((REF / "packs.json").read_text())
    return {p["code"] for p in packs if p.get("category") in ("core", "campaign")}

def investigator_whitelist():
    f = REF / "selected-investigators.json"
    if not f.exists():
        return None
    try:
        data = json.loads(f.read_text())
    except Exception:
        return None
    codes = data.get("codes") if isinstance(data, dict) else data
    codes = {(x["code"] if isinstance(x, dict) else x) for x in (codes or [])}
    return codes or None

# ---------- 欄位轉換 ArkhamDB → CardDefinition(docs/06 §6) ----------
def classes(c):
    out = [c.get(k) for k in ("faction_code", "faction2_code", "faction3_code")]
    return [x for x in out if x in FACTIONS]

def skill_icons(c):
    icons = []
    for k, label in SKILLMAP.items():
        icons += [label] * int(c.get(k) or 0)
    return icons

def traits(c):
    return [t.strip() for t in re.split(r"[.。]", c.get("traits") or "") if t.strip()]

def slots(c):
    raw = c.get("real_slot") or c.get("slot") or ""
    out = []
    for part in re.split(r"[.]", raw):
        part = part.strip()
        if not part:
            continue
        m = re.match(r"(.+?)\s*x?(\d+)?\s*$", part)
        out += [m.group(1).strip().lower()] * int(m.group(2) or 1)
    return out

def stats(c, typ):
    st = {}
    if typ == "investigator":
        st = dict(willpower=c.get("skill_willpower"), intellect=c.get("skill_intellect"),
                  combat=c.get("skill_combat"), agility=c.get("skill_agility"),
                  health=c.get("health"), sanity=c.get("sanity"))
    elif typ == "enemy":
        st = dict(fight=c.get("enemy_fight"), enemyHealth=c.get("health"), evade=c.get("enemy_evade"),
                  enemyDamage=c.get("enemy_damage"), enemyHorror=c.get("enemy_horror"))
    elif typ == "location":
        st = dict(shroud=c.get("shroud"), clueValue=c.get("clues"))
    elif typ == "agenda":
        st = dict(doomThreshold=c.get("doom"))
    elif typ == "act":
        st = dict(clueThreshold=c.get("clues"))
    elif typ == "asset":
        st = dict(health=c.get("health"), sanity=c.get("sanity"))
    return {k: v for k, v in st.items() if v is not None}

def transform(c):
    typ = c.get("type_code")
    level = c.get("xp")
    if level is None and typ in ("asset", "event", "skill"):
        level = 0
    xp = None if level is None else (level * 2 if c.get("exceptional") else level)

    d = {"code": c["code"], "packCode": c.get("pack_code"), "type": typ, "name": c.get("name")}
    if c.get("subname"):        d["subtitle"] = c["subname"]
    cls = classes(c)
    if cls and typ in ("asset", "event", "skill", "investigator"):
        d["class"] = cls
    if level is not None:       d["level"] = level
    if xp is not None:          d["xp"] = xp
    if c.get("cost") is not None: d["cost"] = c.get("cost")
    if traits(c):               d["traits"] = traits(c)
    if skill_icons(c):          d["skillIcons"] = skill_icons(c)
    if slots(c):                d["slots"] = slots(c)
    d["keywords"] = []          # TODO:之後從 text 解析 Hunter/Retaliate…(docs/05 §5)
    if stats(c, typ):           d["stats"] = stats(c, typ)
    if c.get("text"):           d["text"] = c["text"]
    if c.get("flavor"):         d["flavor"] = c["flavor"]
    if c.get("encounter_code"): d["encounterSet"] = c["encounter_code"]
    if typ == "investigator":
        req = {}
        dr = c.get("deck_requirements")
        if isinstance(dr, dict):
            req["raw"] = dr
            if dr.get("size") is not None:
                req["size"] = dr["size"]
        elif isinstance(dr, str) and dr:
            req["raw"] = dr
            m = re.search(r"size:\s*(\d+)", dr)
            if m: req["size"] = int(m.group(1))
        if c.get("deck_options"): req["optionsRaw"] = c["deck_options"]
        if req: d["deckRequirements"] = req
    if c.get("is_unique"):      d["unique"] = True
    if c.get("quantity") is not None: d["quantity"] = c["quantity"]
    d["effects"] = None         # 結構化效果日後補(docs/02 §4)
    d["image"] = f"images/{c.get('pack_code')}/{c['code']}.webp"
    if c.get("double_sided"):
        d["imageBack"] = f"images/{c.get('pack_code')}/{c['code']}.back.webp"
    aw = ARTWORK.get(c["code"])
    if aw and aw.get("artist"):
        d["artist"] = aw["artist"]
    return d

def main():
    scope = scope_pack_codes()
    wl = investigator_whitelist()
    cards = fetch_all()
    OUT.mkdir(parents=True, exist_ok=True)

    kept = []
    for c in cards:
        if c.get("pack_code") not in scope:
            continue
        if c.get("type_code") == "investigator" and wl is not None and c["code"] not in wl:
            continue
        kept.append(c)

    by_pack = {}
    for c in kept:
        by_pack.setdefault(c["pack_code"], []).append(transform(c))
    for pk, lst in sorted(by_pack.items()):
        (OUT / f"{pk}.json").write_text(json.dumps(lst, ensure_ascii=False, indent=1))

    invs = [c for c in kept if c.get("type_code") == "investigator"]
    print(f"\nscope 卡盒 {len(scope)} 個 · 收錄卡 {len(kept)} 張 → 輸出 {len(by_pack)} 檔到 content/cards/generated/")
    print("調查員 " + (f"{len(invs)} 位(依白名單 {len(wl)} 篩選)" if wl else f"{len(invs)} 位(未提供白名單 → scope 內全收)"))
    # 抽樣驗證
    if invs:
        print("\n── 轉換後範例(調查員):")
        print(json.dumps({k: transform(invs[0])[k] for k in ("code","name","type","class","stats","deckRequirements")
                          if k in transform(invs[0])}, ensure_ascii=False, indent=1)[:700])

if __name__ == "__main__":
    main()
