# content/ — 卡片資料庫

卡片資料(JSON)+ 卡圖 + 多語系。**完整規格見 [`../docs/06-card-data-and-images.md`](../docs/06-card-data-and-images.md)。**

## 結構
```
packs.json                 # 卡盒清單
cards/<packCode>/*.json    # 卡片定義(CardDefinition)
images/<packCode>/*.webp   # 卡圖(⚠️ 不進版控)
locales/<lang>/*.json      # 多語系文字
```

## 從哪裡開始
1. 讀規格 [`docs/06`](../docs/06-card-data-and-images.md)。
2. 看範本 [`cards/core/examples.json`](cards/core/examples.json) —— 4 種 type(investigator / asset / enemy / location)的完整範例。
3. `code` 為佔位,請換成真實 **ArkhamDB code**(僅借 code 對應,文字/美術版權見規格 §9)。
4. 欄位名須對齊 [`../protocol/messages.ts`](../protocol/messages.ts)。

## 重要
- `images/` **不進版控**(FFG 版權)。
- 先做 `core`(修訂核心盒 / 當前環境),驗收後再逐盒擴充。
