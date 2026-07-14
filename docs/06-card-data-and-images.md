# 06 · 卡片資料庫與卡圖規範(交付給資料庫開發者)

> **交付對象:** 負責「卡片資料庫 + 卡圖」的開發者。
> **產出被誰用:** Java 規則引擎(`engine/` 的 `CardDefinition`)、前端(`client/` 與 `prototype/deckbuilder.html` 的牌組格/縮圖/預覽/角色卡)。
> **欄位真相來源:** [`protocol/messages.ts`](../protocol/messages.ts)、[`05-rules-engine-spec.md §9`](05-rules-engine-spec.md)。

---

## 1. 範圍(這次要做 / 不做)

**要做:** Revised Core Set(修訂核心盒,當前環境)全卡的**中繼資料 + 規則文字 + 卡圖 + 多語系(繁中/英)**,約 **224 張玩家卡 + 133 張劇本卡**。
**先不做:** 卡牌效果的**結構化編碼**(L1/L2/L3,見 [`02-design-spec.md §4`](02-design-spec.md))—— 那與規則引擎團隊協作,本次只在資料裡**留欄位 + 填規則文字**。

---

## 2. 目錄結構

```
content/
├── packs.json                      # 卡盒/擴充清單(pack 註冊表)
├── cards/
│   └── <packCode>/
│       └── <code>.json             # 一張卡一檔(或整包一個陣列檔,見 §6)
├── images/                         # ⚠️ 不入版控(見 §9 版權)
│   └── <packCode>/
│       ├── <code>.webp             # 正面
│       └── <code>.back.webp        # 背面(雙面卡才有)
└── locales/
    ├── zh-Hant/<packCode>.json     # 繁中文字
    └── en/<packCode>.json          # 英文文字
```

- `<packCode>`:卡盒代碼,如 `core`(修訂核心盒)。
- 一張卡的資料檔與圖檔**用同一個 `code`** 串起來(見 §3)。

---

## 3. 卡片代碼(`code`)命名規則 ★

每張卡有一個**全域唯一、穩定不變**的 `code` 字串,資料與圖檔都以它命名。

- **格式:** 建議採 **ArkhamDB 相容的 5 碼** `PPNNN`(`PP`=卡盒序號、`NNN`=盒內編號),例:`01001`、`01515`。
  - 修訂核心盒的實際 code 以 **ArkhamDB** 上的對應為準(僅借用 code 對應,**文字/美術版權另計**,見 §9)。
- **唯一性:** 全庫不得重複;同名不同版本(等級不同)是**不同 code**。
- 全部**小寫、無空白、無特殊字元**。

### 圖檔命名(與 code 綁定)
| 檔案 | 說明 |
|---|---|
| `<code>.webp` | 卡片**正面**(必填) |
| `<code>.back.webp` | 卡片**背面**(**雙面卡才需要**:地點、幕 Act、密謀 Agenda、部分調查員/敵人 minicard) |

> 例:`01001` → `images/core/01001.webp`(+ 若雙面 `images/core/01001.back.webp`)。

---

## 4. 卡圖規範

| 項目 | 規範 |
|---|---|
| **格式** | 交付 **WebP**(品質 ~82);來源可為 PNG/JPG,由轉檔管線輸出 WebP。 |
| **尺寸** | 標準卡為 **5:7(≈0.714)**。**每張卡只出「一張全圖」**,建議 ≥ **600×838**(2× 清晰度);客戶端會自動縮放給下列三種用途,**不需分別出縮圖**。 |
| **雙面卡** | 地點/幕/密謀/部分調查員與敵人 minicard 需附 `.back.webp`。 |
| **比例例外** | 調查員卡、minicard、劇本參考卡比例可能不同 —— 以實際卡面為準,並在資料填 `orientation`(`portrait`/`landscape`)。 |
| **命名** | 一律 `<code>.webp` / `<code>.back.webp`,小寫。 |
| **缺圖** | 校驗腳本須報出「有資料無圖 / 有圖無資料」的清單(見 §10)。 |

### 客戶端如何用這張圖(圖片插槽)
同一張 `<code>.webp` 會被縮放到多處用途(見 [`prototype/deckbuilder.html`](../prototype/deckbuilder.html)):
- **牌組格磚**(≈95×132)、**搜尋結果縮圖**(≈34×47)、**卡片預覽**(大圖)、**角色卡**、**遊戲桌面**(敵人/地點卡)。
→ 所以**每張卡只需一張正面全圖(+ 背面)**,不必逐用途出圖。

---

## 5. 卡片類型與各自要填的欄位

| type | 特有欄位(`stats` 內) |
|---|---|
| `investigator` | `willpower`,`intellect`,`combat`,`agility`,`health`,`sanity` + `deckRequirements` |
| `asset` | `cost`,`slots`,(可有 `health`/`sanity`) |
| `event` | `cost` |
| `skill` | (僅 `skillIcons`) |
| `enemy` | `fight`,`enemyHealth`,`evade`,`enemyDamage`,`enemyHorror`,`keywords` |
| `location` | `shroud`,`clueValue`,`connections` |
| `agenda` | `doomThreshold` |
| `act` | `clueThreshold` |
| `treachery` | (規則文字為主) |

---

## 6. 卡片資料 Schema(`CardDefinition`)★

一張卡一筆 JSON(欄位名須對齊 [`messages.ts`](../protocol/messages.ts) / 引擎,才能被反序列化)。**只填該 type 相關欄位**,其餘省略。

```jsonc
{
  "code": "01001",                       // §3 唯一代碼
  "packCode": "core",
  "type": "investigator",                // §5
  "name": "Joe Diamond",                 // 顯示名(預設英文;多語系見 §7)
  "subtitle": "The Private Investigator",
  "class": ["seeker"],                   // 玩家卡職業;中立 = ["neutral"];多職業可多值
  "level": 0,                            // 0–5;調查員/遭遇卡為 0/省略
  "xp": 0,                               // 通常 = level;exceptional 卡 = 2×level
  "cost": null,                          // 支援/事件費用
  "traits": ["Detective"],
  "skillIcons": ["INTELLECT", "WILD"],   // 技能圖示:WILLPOWER/INTELLECT/COMBAT/AGILITY/WILD
  "slots": [],                           // 佔用欄位:hand/arcane/ally/body/accessory/tarot
  "keywords": [],                        // HUNTER/RETALIATE/ALERT/ALOOF/... (§ docs/05 §5)
  "stats": {                             // 依 type 填(§5)
    "willpower": 2, "intellect": 4, "combat": 3, "agility": 3,
    "health": 7, "sanity": 7
  },
  "connections": [],                     // 地點:相連地點 code
  "text": "After you successfully investigate: Draw 1 card. (Limit once per round.)",
  "flavor": "…",                         // 風味文字(可選)
  "deckRequirements": {                  // 調查員專用
    "size": 30,
    "allowed": ["seeker:0-5", "neutral:0-5", "guardian:0-2"],
    "signatures": ["01006"],             // 招牌卡 code
    "signatureWeaknesses": ["01007"]     // 招牌弱點 code
  },
  "elderSignEffect": "+1. If you succeed, draw 1 card and gain 1 resource.",
  "encounterSet": null,                  // 遭遇卡所屬集合(如 "spreading_flames")
  "quantity": 1,                         // 該盒內張數
  "orientation": "portrait",             // portrait/landscape(§4 例外)
  "effects": null,                       // ⛔ 本次留空:結構化效果日後補(docs/02 §4)
  "image": "images/core/01001.webp",
  "imageBack": null                      // 雙面卡填 "images/core/01001.back.webp"
}
```

> **只填有值的欄位**;沒有的省略或 `null`。`type` 決定哪些欄位有意義(§5)。

---

## 7. 多語系(i18n)

文字與資料分離。兩種做法擇一(建議 A):

- **A. 資料檔放語言中立結構,文字放 `locales/`:** `locales/zh-Hant/core.json` 以 `code` 為 key:
  ```jsonc
  { "01001": { "name": "喬·戴蒙", "subtitle": "私家偵探", "text": "…", "traits": ["偵探"] } }
  ```
- **B. 卡片 JSON 內嵌 `locale`:** `"locale": { "zh-Hant": {...}, "en": {...} }`。

修訂核心盒有**官方繁中**,`name`/`text` 請優先採官方譯名。

---

## 8. 效果編碼(本次界定)

- 本次**只填 `text`(規則文字)**,`effects` 留 `null`。
- 之後與規則引擎團隊一起,把常見效果做成 **L1 資料驅動 / L2 效果 DSL**,刁鑽卡 **L3 腳本**(見 [`02-design-spec.md §4`](02-design-spec.md))。屆時 `effects` 欄位承接。

---

## 9. 資料來源與版權 ⚠️(必讀)

- **結構可參考社群 [ArkhamDB](https://arkhamdb.com/) 的開放 JSON**(GitHub 搜 `arkhamdb-json-data`)—— **僅借用「欄位結構與 code 對應」**。
- **卡片文字、美術、劇本 © Fantasy Flight Games / Asmodee。** 見 [`04-roadmap-risks.md` R-01](04-roadmap-risks.md)。
- **規則:**
  1. **卡圖不進版控,且 app 不散布卡圖** —— repo 只放 `*.json` 結構(`content/.gitignore` 已排除 `images/`)。**採「玩家自帶卡圖(BYO)」:每位玩家在自己的系統提供卡圖,app 只有資料 → IP 中立**(載入策略見 §12)。
  2. 商業發行前,卡圖與文字須取得授權;未授權**勿公開散布**含官方素材的版本。
  3. code / 欄位結構本身無版權疑慮,可自由使用。

---

## 10. 交付物(Definition of Done)

1. `content/packs.json`:含 `core`(修訂核心盒)。
2. `content/cards/core/*.json`:修訂核心盒**全卡**(224 玩家 + 133 劇本),欄位符合 §6。
3. `content/locales/zh-Hant/core.json` + `en/core.json`:對應文字。
4. `content/images/core/*.webp`:每卡 1 正面(雙面卡加背面),命名符合 §3–4(**放私有資產庫,不進 repo**)。
5. **校驗腳本**(node/python 皆可):
   - `code` 全庫唯一;
   - 每張卡 `image` 路徑存在(或列出缺圖清單);
   - 必填欄位齊全、`type` 對應欄位正確(§5);
   - 能被引擎 `CardDefinition` 反序列化(欄位名對齊 `messages.ts`)。
6. **先交 `core`(當前環境)**,驗收後再逐盒擴充(legacy 為長期)。

## 11. 驗收檢查(Reviewer checklist)

- [ ] `code` 唯一、命名合規、圖檔與資料一一對應、無缺圖。
- [ ] Schema 欄位齊全且 type 對應正確;能反序列化。
- [ ] 繁中/英文字齊全,繁中採官方譯名。
- [ ] `images/` 未進版控;無公開散布官方素材。
- [ ] 抽樣 10 張與實體卡核對數值/文字無誤。
- [ ] 卡圖採「玩家自帶」;app / repo 不含官方美術。

---

## 12. 卡圖載入策略(玩家自帶 + 依需求預載)

**原則:app / repo 不散布任何卡圖(§9)。採「玩家自帶(BYO)」** —— 每位玩家在自己的系統提供卡圖資料夾(命名照 §3 `<code>.webp`);app 只有卡片資料 → **IP 中立**。**有圖顯示圖、沒圖顯示風格化卡磚**(優雅降級;選卡器已內建 placeholder)。

**客戶端如何讀玩家的圖:**
| Client | 讀取方式 |
|---|---|
| Tauri 桌面(推薦) | 玩家在設定指定「卡圖資料夾」,直接讀本機檔(無瀏覽器沙箱) |
| 純瀏覽器 | File System Access API(選一次資料夾)或匯入 zip → 存 IndexedDB 快取 |

**前端載入 vs 預載(效能):**
- **遊戲桌面同時可見的卡少(幾張)→ lazy(需要才載)即可,零問題。**
- **選卡器一次顯示 ~50 張(牌組格 + 搜尋清單)= 唯一壓力點 → 開牌組/開場時「批次預載該範圍卡圖」**(即「預載配件」),之後渲染即時。
- 搭配:① 以 `code` 為 key 的**解碼快取**(ImageBitmap);② 格子磚**縮圖降解析度**(~95px 即可,預覽才用全圖);③ 缺圖 **fallback 到風格化卡磚**。
- 結論:**lazy 為預設;選卡器/開場採預載** —— 與「發動卡片不延遲」同一原則(一次性小成本換即時)。

---

*本規格對應:[`protocol/messages.ts`](../protocol/messages.ts)(欄位)、[`05-rules-engine-spec.md`](05-rules-engine-spec.md)(資料模型)、[`02-design-spec.md`](02-design-spec.md)(效果系統)、[`prototype/deckbuilder.html`](../prototype/deckbuilder.html)(圖片插槽)。*
