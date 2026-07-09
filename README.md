# Arkham Horror: The Card Game — 電子化

「阿卡姆驚魂 卡牌遊戲」的數位化實作。**完整規則引擎**(權威伺服器)＋ **全內容**為目標,初期支援**區域網路多人**。

> ⚠️ **商業發行前提:** 卡牌文字/美術/劇本/商標為 Fantasy Flight Games / Asmodee 版權,商業化前須先解決 IP 授權。見 [docs/04 · R-01](docs/04-roadmap-risks.md)。技術與此獨立(引擎可複用)。

## 架構一句話

**權威伺服器**:Java 規則引擎(headless、確定性)跑所有規則;客戶端(TypeScript + PixiJS)只送**意圖**、收**過濾後狀態**,經 **WebSocket** 溝通。LAN 時由某位玩家的主機內嵌伺服器;商業版同一套搬上雲。

```
┌── client/ (TS + PixiJS) ──┐   WebSocket    ┌── server/ (Java, Spring Boot) ──┐
│  只渲染狀態 / 送意圖        │ ⇄ JSON 意圖/狀態 │  WebSocket 會話 + 房間           │
└───────────────────────────┘                │  └── engine/ (Java, headless)   │
        ▲ 契約: protocol/                     │        規則引擎:狀態機/檢定/     │
        └─────────────────────────────────────┘        混沌袋/效果/時機           │
```

## 目錄結構

| 目錄 | 內容 |
|---|---|
| [`docs/`](docs/) | **設計書**:規則整理、系統設計、技術盤點、路線圖與風險、規則引擎規格。從 [docs/README.md](docs/README.md) 讀起。 |
| [`rulebook/`](rulebook/) | 官方規則書 PDF(Revised Core Set)。 |
| [`prototype/`](prototype/) | **可操作網頁原型**(單檔):圖版分佈、戰鬥、8 步技能檢定、投入判定、敵人階段(獵人)、神話/密謀。當「畫面規格」的活文件。 |
| [`protocol/`](protocol/) | **連線協定契約**(client ⇄ server 的唯一真相):`protocol.md` + `messages.ts`。 |
| [`engine/`](engine/) | **Java 規則引擎**(headless、確定性、可測):GameState、ChaosBag、SkillTest、RulesEngine。 |
| [`server/`](server/) | **Java 遊戲伺服器**(Spring Boot + WebSocket):把引擎包成連線服務。 |
| [`client/`](client/) | **前端客戶端**(TypeScript + Vite + PixiJS):渲染 + 送意圖。 |

## 快速開始

> **前置需求:** 一套 **JDK**(理想 21;若只有較舊版,Gradle 會透過 foojay 自動下載 21 toolchain)、**Node 18+**、首次執行需**網路**(自動下載 Gradle / JDK / 相依)。**Gradle wrapper 已內含**,不需另裝 Gradle。

**一鍵端到端**(伺服器 + 前端,Ctrl+C 一起關閉):
```bash
./run-dev.sh     # 前端 http://localhost:5173 · 伺服器 ws://localhost:8080/ws/game
```

或分開跑:
```bash
./gradlew :engine:test                     # 規則引擎單元測試
./gradlew :server:bootRun                  # 遊戲伺服器 @ ws://localhost:8080/ws/game
cd client && npm install && npm run dev    # 前端 @ http://localhost:5173
```

**只看原型**(不需上面任何服務):
```bash
python3 -m http.server 8131 --directory prototype   # 開 http://localhost:8131
```

## 現況

- ✅ 設計書 6 份、規則引擎規格(對齊官方規則書)
- ✅ 可操作原型(兩大流程 + 投入判定 + 神話/多敵人,已瀏覽器實測)
- ✅ 連線協定契約(`protocol/`)
- ✅ 正式骨架:`engine/`(Java 21 規則引擎,主原始碼編譯通過 50 類)+ `server/`(Spring Boot WebSocket)+ `client/`(TS + PixiJS)
- ⬜ 內容規模化、帳號/雲端、決策 UI(投入判定)接上前端、跨 client/server 端到端連通測試(需 JDK 21 + Gradle)

分期路線見 [docs/04-roadmap-risks.md](docs/04-roadmap-risks.md)。
