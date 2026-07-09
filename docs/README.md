# Arkham Horror: The Card Game — 電子化專案設計書

> 本目錄是「阿卡姆驚魂 卡牌遊戲(Arkham Horror: The Card Game, 以下簡稱 **AHLCG**)」電子化的設計與技術盤點文件集。

## 專案定位(依需求確認)

| 決策項 | 你的選擇 | 影響 |
|---|---|---|
| **規則自動化程度** | **完整規則引擎**(軟體強制執行所有規則、自動判定卡牌效果) | 技術難度最高;核心是打造一套「卡牌效果引擎 + 時機/優先權系統」 |
| **內容範圍** | **全部已發行內容**(所有調查員、戰役) | 資料量龐大;必須採「資料驅動 + 腳本化」的通用卡牌引擎 |
| **用途規模** | **商業發行** | 需帳號、雲端、金流、內容更新;**且需先解決 IP 授權(見風險 R-01)** |
| **初期連線** | 區域網路(LAN)多人 | 採「主機即權威伺服器」模型,設計上預留雲端化 |

> ⚠️ **頭號前提(必讀):** AHLCG 的卡牌文字、美術、劇本、商標皆為 **Fantasy Flight Games / Asmodee** 版權。**商業發行**未經授權即屬侵權,無法合法販售。技術設計與此獨立(引擎本身是合法且可複用的資產),但商業化之前必須先解決授權。詳見 [04-roadmap-risks.md](04-roadmap-risks.md) 的 **R-01**。

## 一句話結論

- **能不能用 Java?** → **能,而且是強項。** 業界已有兩套大型「Java + 主從式 + 完整規則引擎」的卡牌遊戲前例(**XMage**、**Forge**,皆為 Magic: The Gathering 引擎),直接證明可行性。建議 **後端/規則引擎用 Java**;客戶端可用 Java(JavaFX 或 LibGDX)或另擇前端技術,關鍵是「規則引擎與 UI 徹底解耦、可無頭(headless)執行」。
- **資料要不要放 server?** → **分兩類看**:
  - 靜態內容(卡表、圖):LAN MVP 可隨客戶端打包;商業版建議由內容伺服器/CDN 版本化派送。
  - 動態資料(帳號、擁有內容、牌組、戰役存檔、對局狀態):**商業版一定要 server 端儲存**;純 LAN 自用可由主機本機存檔,但**戰役存檔建議即使 LAN 也用共享儲存**(多人共用一場長期戰役)。
- **務實建議:** 架構「按全內容設計」,但**實作從核心盒(Core Set)起步**,一個資料夾一個資料夾地擴充。完整規則引擎 + 全內容是**多人年**等級的工程(XMage 已開發 10 年以上),切勿一次吃下。

## 文件導覽

| 檔案 | 內容 |
|---|---|
| [01-game-rules.md](01-game-rules.md) | **規則與玩法整理** — 遊戲組成、回合結構、技能檢定、混沌袋、戰鬥/調查、戰役系統、牌組構築。電子化時的規則抽象化重點。 |
| [02-design-spec.md](02-design-spec.md) | **系統設計書** — 功能盤點(功能地圖)、系統架構、規則引擎設計(效果/時機/優先權)、資料模型、卡牌腳本化方案。 |
| [03-tech-requirements.md](03-tech-requirements.md) | **技術需求盤點** — 核心技術需求、Java 可行性評估、連線架構(LAN→雲端)、資料儲存策略、技術選型建議。 |
| [04-roadmap-risks.md](04-roadmap-risks.md) | **開發路線圖與風險** — 分期里程碑、工作量量級估算、風險清單(含 IP 授權)、關鍵決策待辦。 |
| [05-rules-engine-spec.md](05-rules-engine-spec.md) | **規則引擎規格** — 直接取自官方規則書(`rulebook/ahc100_rulebook-web.pdf`)的引擎行為合約:回合序列、技能檢定 8 步、能力/時機分類、**關鍵字登記表**、卡牌解剖→資料欄位、易漏規則測試案例。 |
| [06-card-data-and-images.md](06-card-data-and-images.md) | **卡片資料庫與卡圖規範** — 交付給資料庫開發者:目錄結構、`code` 命名規則、卡圖格式/尺寸/命名、`CardDefinition` schema、多語系、版權界線、交付物與驗收。 |
| [07-lan-setup.md](07-lan-setup.md) | **區域網路(LAN)連線** — 怎麼啟動 server 端與 client 端;任一玩家皆可當主機;瀏覽器加入、房間/調查員參數、防火牆疑難。 |
| [08-save-and-provenance.md](08-save-and-provenance.md) | **存檔與溯源** — 戰役/對局/牌組版本三種存檔;不可變版本 + 釘選,讓每筆遊戲紀錄可回推當時牌組與演變;seed + 事件流重播。 |

## 建議閱讀順序

1. 先讀本頁(定位與結論)
2. 決策者/PM → 直接看 [04-roadmap-risks.md](04-roadmap-risks.md)(規模、風險、分期)
3. 想懂遊戲 → [01-game-rules.md](01-game-rules.md)
4. 工程師 → [02-design-spec.md](02-design-spec.md) → [05-rules-engine-spec.md](05-rules-engine-spec.md)(引擎合約)→ [03-tech-requirements.md](03-tech-requirements.md)

## 依據來源

- **官方規則書:** `rulebook/ahc100_rulebook-web.pdf` — **Revised Core Set(修訂核心盒,© 2026 FFG)**,48 頁,含 Learn to Play + 縮節版 Arkham Grimoire(關鍵字glossary)。本設計書的規則已對齊此版本。
- **核心盒內容:** 5 位調查員(Daniela Reyes / Guardian、Joe Diamond / Seeker、Trish Scarborough / Rogue、Dexter Drake / Mystic、Isabelle Barnes / Survivor)、**Brethren of Ash** 戰役(2 劇本:*Spreading Flames*、*Smoke and Mirrors*)、224 張玩家卡 + 133 張劇本卡。
- **完整裁決權威:** 線上 **Arkham Grimoire**(活規則文件,隨新卡更新)。引擎的裁決邏輯應以 Grimoire 為最終依據,規則書為入門。
- **內容範圍槓桿:** 官方區分 **Current(當前)環境**(策展小卡池,推薦)與 **Legacy(遺產)環境**(含絕版強力連段)。「全內容」建議先做當前環境(見 [05](05-rules-engine-spec.md) §11、[04](04-roadmap-risks.md))。

---
*本文件為初版設計盤點,供討論與逐步細化。標註 `【待定】` 者為需你拍板的決策點。*
