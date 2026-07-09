# Arkham 電子化原型(Vertical Slice)

`index.html` — **單檔可執行的網頁原型**,展示兩個核心流程的可操作畫面:

- **① 圖版分佈流程**:移動 → 進入未揭示地點 → 揭示 + 依 clueValue 放線索 → 可能生成敵人(宿舍會生成烈焰僕從)。
- **② 戰鬥流程**:交戰 / 戰鬥 / 閃避 → **8 步技能檢定 + 混沌袋抽取** → 成功/失敗判定 → 關鍵字(Retaliate 反擊 / Hunter 獵人)→ 傷害/恐懼結算。

另含:3 行動經濟、敵人階段(獵人移動 + 攻擊)、整備、線索推進幕(獲勝)、被擊敗(失敗)。

## 執行方式
用瀏覽器直接開 `index.html`,或起靜態伺服器:
```bash
python3 -m http.server 8131 --directory prototype
# 瀏覽 http://localhost:8131
```

## 這是什麼 / 不是什麼
- **是**:前端 UI 與遊戲流程的可操作驗證,也是客戶端的「畫面規格」活文件。
- **不是**:正式架構。原型把遊戲邏輯寫在**瀏覽器端**方便展示;**正式版必須把規則邏輯搬到 Java「權威伺服器」**(headless 規則引擎),客戶端只送意圖、收狀態(見 [`../docs/02-design-spec.md`](../docs/02-design-spec.md)、[`../docs/03-tech-requirements.md`](../docs/03-tech-requirements.md))。

## 程式如何對應設計書
檔內 JS 刻意分層 `State / ChaosBag / SkillTest / Actions / Render`:
| 原型程式 | 對應規格 |
|---|---|
| `runSkillTest()`(8 步 + 2 player window)| [`docs/05`](../docs/05-rules-engine-spec.md) §2 技能檢定 |
| `freshBag()` / `SYMBOL` | docs/05 §6 混沌標記 |
| `doMove/doInvestigate/doFight/doEvade` + `attackOfOpportunity()` | docs/05 §4 行動與趁隙攻擊 |
| Hunter / Retaliate 處理、`nextStepToward()` BFS | docs/05 §5 關鍵字登記表 |
| `endTurn()`(敵人階段→整備→下一輪)| docs/05 §1 回合序列 |

## 技術選型
前端建議 **TypeScript + PixiJS + HTML/CSS**,WebSocket 接 Java 引擎,**Tauri** 打包桌面/Steam/LAN。本原型用 vanilla JS 以求「單檔即可跑」;正式版改 TS + 建置工具、把邏輯移到伺服器。
