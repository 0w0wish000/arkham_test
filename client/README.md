# Arkham 客戶端(TypeScript + PixiJS)

前端「觀景窗」:只渲染伺服器下發的 `GameStateView`、把使用者操作轉成**意圖 (intent)**。**不含任何規則邏輯** —— 規則全在 Java 權威伺服器。

## 開發
```bash
cd client
npm install
npm run dev        # http://localhost:5173(經 Vite proxy 連 ws://localhost:8080/ws/game)
```
需先啟動 Java 伺服器:`./gradlew :server:bootRun`(在 repo 根)。

## 結構
| 檔案 | 職責 |
|---|---|
| `src/net/Connection.ts` | WebSocket 連線;送 intent、收 state/event/choice |
| `src/state/ClientStore.ts` | 保存最新的過濾視圖(唯讀) |
| `src/render/GameView.ts` | PixiJS 渲染地圖 + 點擊相連地點送 `MOVE` |
| `src/protocol.ts` | 重新匯出共享協定型別(`../protocol/messages.ts`) |

## 待辦(TODO)
- 決策 UI:`CHOICE_REQUEST` 的 `COMMIT_CARDS`(投入判定畫面)—— 對應 [`../prototype/`](../prototype/) 已做好的投入畫面,搬成 Pixi/HTML 疊層。
- 戰鬥/調查/技能檢定的動畫;敵人與 token 的渲染細節。

型別契約見 [`../protocol/protocol.md`](../protocol/protocol.md)。前端定位與選型理由見 [`../docs/03-tech-requirements.md`](../docs/03-tech-requirements.md) §2。
