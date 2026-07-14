#!/usr/bin/env python3
"""
build_locale_template.py — 產出繁中補譯模板(docs/11 §G2)。

讀 generated/<pack>.json,對照既有 locales/zh-Hant/<pack>.json(已譯者略過),
輸出待譯模板到 locales/_templates/<pack>.zh-Hant.todo.json(含英文原文 → gitignored,
FFG 文字不入庫;譯完把「純譯文」搬進 locales/zh-Hant/<pack>.json 再提交)。

用法:  python3 content/tools/build_locale_template.py [packCode…]   # 預設 core_2026
"""
import json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GEN, LOC = ROOT/"content/cards/generated", ROOT/"content/locales"

def run(pack):
    src = GEN / f"{pack}.json"
    if not src.exists():
        print(f"✗ {pack}:找不到 generated(先跑 setup-content)"); return
    done = {}
    f = LOC / "zh-Hant" / f"{pack}.json"
    if f.exists():
        done = {k: v for k, v in json.loads(f.read_text()).items() if not k.startswith("_")}
    todo = {}
    for c in json.loads(src.read_text()):
        code = c.get("code")
        if not code or code in done:
            continue
        entry = {"name": "", "text": ""}
        if c.get("subtitle"): entry["subtitle"] = ""
        if c.get("traits"):   entry["traits"] = []
        entry["_en"] = {k: c[k] for k in ("name", "subtitle", "traits", "text") if c.get(k)}
        todo[code] = entry
    out = LOC / "_templates"; out.mkdir(parents=True, exist_ok=True)
    (out / f"{pack}.zh-Hant.todo.json").write_text(json.dumps(todo, ensure_ascii=False, indent=1))
    print(f"✓ {pack}:待譯 {len(todo)} 張(已譯 {len(done)})→ locales/_templates/{pack}.zh-Hant.todo.json")

for p in (sys.argv[1:] or ["core_2026"]):
    run(p)
