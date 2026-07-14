# content/reference/ — 官方卡片索引(來源:ArkhamDB)

由 [ArkhamDB](https://arkhamdb.com/) 公開 API 抓的**官方**卡片索引(非玩家自製 —— arkhamdb.com 只收官方 FFG 卡)。**IP-light**:只存 code / 名稱 / 分類等索引欄位,不倒入全文與美術。

| 檔案 | 內容 |
|---|---|
| `packs.json` | 全 114 個官方卡盒,含 `category` 分類 + 卡數/角色數 |
| `investigators.json` | 105 位官方調查員索引(code / name / faction / 生命理智 / 圖片路徑) |
| `selected-investigators.json` | **調查員白名單**:填入 `codes` 只做那些;空 = 全收 scope 內 |
| `artwork.json` | 繪師/美術歸屬(3249 張,以 `code` 為 key;**已套用 Artist Fixes**),由 `build_artwork.py` 從使用者提供的「Arkham Artwork.xlsx」轉出。`build_cards.py` 會把 `artist` 併進卡定義 |
| `characters-in-art.json` | 哪些角色出現在哪張卡的美術裡(301 筆) |

## 分類(`category`)
`core`(核心盒)· `campaign`(★ 主線 8 戰役)· `return_to` · `investigator_pack` · `standalone` · `novella` · `parallel_challenge`。
**「官方標準擴充」= `core` + `campaign` = 4408 卡 · 80 角色**。

## 取得完整卡料(converter)
卡牌**文字為 FFG 版權** → 用腳本在本機生成、不入公開版控。

```bash
python3 content/tools/build_cards.py
```
- 抓 ArkhamDB(篩 `packs.json` 的 `category ∈ {core, campaign}`)→ 轉成 [docs/06](../../docs/06-card-data-and-images.md) 的 `CardDefinition` schema。
- 輸出:`content/cards/generated/<packCode>.json`(**gitignored**)。本次 = **65 盒 / 4408 卡 / 80 角色**。
- **調查員白名單**:編輯 `selected-investigators.json` 的 `codes`,再重跑 → 只做那些角色。
- 卡圖維持**玩家自帶**(docs/06 §12);腳本不下載圖。
- 待補:`keywords`(由 text 解析)、`deckRequirements.optionsRaw` → 我們的 `allowed` 構築規則格式、地點 `connections`。
