#!/usr/bin/env python3
"""
build_campaigns.py — 從 generated 卡資料推導「每條戰役的章節清單」(docs/11 D1 種子)。

依 packs.json 的 cycle 分組(category ∈ {core, campaign}),取各包的 scenario 參考卡、
按卡片 code 全域序排章節;去雜訊(同名相鄰重複只留第一筆、code 字尾字母僅影響排序)。

輸出 content/reference/campaigns.json(入 git:僅章節「名稱與順序」的索引,
不含卡文;敘事/分支不在卡 API,見 docs/11 §G 註)。maxXpAward 待後續補(docs/09 §11)。

用法:  python3 content/tools/build_campaigns.py   (需先跑 build_cards.py)
"""
import json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REF  = ROOT / "content" / "reference"
GEN  = ROOT / "content" / "cards" / "generated"

def main():
    if not GEN.exists() or not any(GEN.glob("*.json")):
        raise SystemExit("找不到 content/cards/generated/ —— 先跑 setup-content 或 build_cards.py")
    packs = {p["code"]: p for p in json.loads((REF / "packs.json").read_text())}

    by_cycle = {}
    for f in sorted(GEN.glob("*.json")):
        pack = packs.get(f.stem)
        if not pack or pack.get("category") not in ("core", "campaign"):
            continue
        for c in json.loads(f.read_text()):
            if c.get("type") == "scenario":
                by_cycle.setdefault(pack["cycle"], []).append(
                    {"code": c.get("code", ""), "pack": f.stem, "name": c.get("name", "")})

    def sort_key(s):  # code 可能帶字尾字母(如 11688a)
        m = re.match(r"(\d+)", s["code"])
        return (int(m.group(1)) if m else 0, s["code"])

    campaigns = []
    for cyc in sorted(by_cycle):
        scen = sorted(by_cycle[cyc], key=sort_key)
        chapters, seen = [], None
        for s in scen:
            if s["name"] == seen:      # 去雜訊:同名相鄰(雙面參考卡)只留第一筆
                continue
            seen = s["name"]
            chapters.append(s)
        first_pack = packs[chapters[0]["pack"]]
        campaigns.append({
            "key": chapters[0]["pack"],          # 大廳 campaignKey 用(首包代碼)
            "name": first_pack["name"],
            "cycle": cyc,
            "chapters": chapters,                 # [{code, pack, name}] 依序
            "maxXpAwardPerChapter": None,         # 待補:docs/09 §11 經驗上限精算
        })

    out = {
        "_comment": "由 build_campaigns.py 從卡資料推導的戰役章節索引(僅名稱/順序;無敘事/分支)。",
        "campaigns": campaigns,
    }
    (REF / "campaigns.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    print(f"✓ {len(campaigns)} 條戰役 → content/reference/campaigns.json")
    for c in campaigns:
        print(f"  cycle {c['cycle']:>2}  {c['name']:<28} {len(c['chapters'])} 章(key={c['key']})")

if __name__ == "__main__":
    main()
