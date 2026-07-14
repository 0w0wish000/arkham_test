# Arkham 連線協定(Client ⇄ Server Protocol)

> 客戶端(TypeScript/PixiJS)與伺服器(Java 規則引擎)之間的**唯一契約**。
> 兩邊都必須遵守本檔:TS 型別見 [`messages.ts`](messages.ts);Java 端以對應的 record/enum 鏡射(`server` 模組)。

## 原則

- **權威伺服器(authoritative server):** 客戶端只送「意圖 (intent)」與「決策回應」,**所有規則判定在伺服器**。客戶端絕不自行改遊戲狀態。
- **每客戶端過濾視圖:** 伺服器為每位玩家送**只該他看到**的 `GameStateView`(隱藏遭遇牌堆順序、他人手牌等)。
- **傳輸:** WebSocket,端點 `ws://<host>:8080/ws/game`,以 **JSON 文字幀**傳遞。
- **訊息信封:** 每則訊息都是 `{ "type": "...", ... }`。`type` 為下列列舉之一。
- **玩家順序彈性(合作遊戲):** 調查階段誰先行動由團隊**自選**,伺服器**不強制固定輪替**(`activeInvestigatorId` 可由團隊指定 / 自願接手);投入協助 **先丟先算(first-come-first-served)**。僅「必做且同時發生」的步驟(神話抽卡、多敵攻擊)才套座位順序。詳見 [docs/05 §4.1](../docs/05-rules-engine-spec.md)。

## 大廳 / 存檔驅動(docs/09)

> **取代 room**:連上主機後先 `HELLO` 自報身分(`playerId`),再從 `LOBBY` 的「進行中桌次」建桌 / 加入桌。桌 = 一個 campaign session;`SESSION_ROSTER` 持續廣播名冊與屏障進度。細節見 [docs/09](../docs/09-lobby-save-handshake.md)。P1 僅到牌組大廳(DECKBUILDING);進戰役(START_SCENARIO / ENTER_SCENARIO)為後續階段。

| Client → Server | 欄位 | 說明 |
|---|---|---|
| `HELLO` | `playerId`, `displayName` | 連上後自報身分;主機回 `LOBBY` |
| `CREATE_CAMPAIGN` | `name`, `campaignKey`, `difficulty` | 開新檔案 → 建一桌(stage=DECKBUILDING) |
| `JOIN_SESSION` | `campaignId` | 加入一張進行中桌次 |
| `LEAVE_SESSION` | — | 離桌回主選單 |
| `PICK_INVESTIGATOR` | `investigatorId` | 牌組大廳選角(§8.2;不得選已被隊友選走者) |
| `SET_DECK` | `deck`, `xp` | 提交/更新牌組(deck=卡名清單) |
| `READY_DECK` | `ready` | 牌組完成/反悔(屏障 B);全員 ACTIVE 就緒→開打 |
| `FORCE_START` | — | 主機強制越過屏障開打 |
| `OFFER_SAVE` | `save` | 用本機存檔開/續桌(§7);依 stage 還原 |
| `READY_LOAD` | `ready` | 我已載入戰役快照(屏障 A) |
| `SIT_OUT` | `sitOut` | 本章中離/歸隊(§9);中離者不參戰、不擋屏障、難度隨人數 |
| `PROPOSE_NEW_CHARACTER` | `playerId` | 對死亡者發起換角投票(§10) |
| `VOTE` | `requestId`,`yes` | 換角投票 |

新增 Server → Client:

| type | 欄位 | 說明 |
|---|---|---|
| `CAMPAIGN_SNAPSHOT` | `save` | 全戰役存檔(名冊+牌組+快照+eventLog+deadInvestigators+maxXp)複製到各本機 |
| `LOG_HISTORY` | `entries` | 載入後回放的出牌/事件紀錄 |
| `VOTE_PROMPT` | `requestId`,`subject`,`reason` | 換角投票彈窗 |

> 開打(START_SCENARIO)後,伺服器用名冊所選調查員建場景,對每位玩家推送初始 `STATE`(客戶端據此從大廳切到戰役板);之後即走上面的 `INTENT`/`CHOICE_RESPONSE`/`SAVE_*` 流程。
> 載入:`OFFER_SAVE`(戰役中)→ `LOADING` → 各人 `READY_LOAD` 屏障 A → 重建對局 + `LOG_HISTORY` + `STATE`。接手:戰役中掉線由**原玩家**重連 `JOIN_SESSION` → 伺服器 `reattach` 送 `STATE` + 補發卡住的 `CHOICE_REQUEST`(重打掉線那一動)。

| Server → Client | 欄位 | 說明 |
|---|---|---|
| `LOBBY` | `activeSessions[]` | 進行中桌次清單(前端再併「本機存檔」清單) |
| `SESSION_ROSTER` | `campaignId`,`name`,`campaignKey`,`stage`,`difficulty`,`members[]`,`canForce` | 名冊 + 屏障進度(驅動大廳/waiting UI) |

> 身分是否需要輸入(profile 有無)是**純前端**判斷(讀本機 `localStorage`),不走協定。

## Client → Server

| type | 欄位 | 說明 |
|---|---|---|
| `JOIN` | `sessionId`, `investigatorId` | 加入一場對局(LAN:輸入主機給的房號) |
| `INTENT` | `action`, `payload?` | 玩家想執行的行動(見下表);伺服器驗證後結算 |
| `CHOICE_RESPONSE` | `requestId`, `choice` | 回應伺服器的 `CHOICE_REQUEST`(如投入哪些卡) |
| `SAVE_REQUEST` | — | 玩家發起「保存並離開」;伺服器隨即向全員發 `SAVE_PROMPT`(見 docs/08 §6.5) |
| `SAVE_VOTE` | `requestId`, `vote` | 回應存檔提示(是/否) |
| `RESUME` | `state` | host 送回存檔的 `state` → 伺服器反序列化成 `GameState` 重建對局(重開載入) |
| `PING` | — | 保活 |

### `INTENT.action`(對應 [docs/05 §4](../docs/05-rules-engine-spec.md))
| action | payload | 對應規則 |
|---|---|---|
| `MOVE` | `{ toLocationId }` | 移動到相連地點(揭示+放線索) |
| `INVESTIGATE` | `{}` | 智力 vs 遮蔽 → 取線索 |
| `FIGHT` | `{ enemyId }` | 戰鬥 vs 戰鬥值 |
| `EVADE` | `{ enemyId }` | 敏捷 vs 閃避值 |
| `ENGAGE` | `{ enemyId }` | 交戰 |
| `PLAY_CARD` | `{ cardId, targets? }` | 打出支援/事件 |
| `ACTIVATE` | `{ cardId, abilityIndex }` | 啟動能力 |
| `END_TURN` | `{ force? }` | 「我打完了」(屏障:**全員**完成才結算敵人/整備/神話);`force:true` = 強制全體結束 |
| `ADVANCE_ACT` | `{}` | 花費線索推進幕 |

## Server → Client

| type | 欄位 | 說明 |
|---|---|---|
| `STATE` | `view: GameStateView` | 該客戶端的過濾後完整狀態(每次變動後推送) |
| `EVENT` | `event`, `message` | 動作播報 / 動畫事件(供 log 與特效) |
| `CHOICE_REQUEST` | `requestId`, `kind`, `options` | 需要該玩家做決定(見下) |
| `ERROR` | `message` | 非法意圖或錯誤 |
| `SAVE_PROMPT` | `requestId`, `requestedBy` | 有玩家要保存 → 各客戶端彈窗「是否存檔?」 |
| `SAVE_SNAPSHOT` | `scenario`, `round`, `state`, `eventLog` | 存檔確認後,把狀態文本 + 出牌紀錄複製給每位玩家寫入本機(離線備份) |
| `PONG` | — | 保活回應 |

### `CHOICE_REQUEST.kind`
| kind | options | 說明 |
|---|---|---|
| `COMMIT_CARDS` | `{ skill, base, difficulty, eligibleCards[], maxCommit }` | **技能檢定投入**(檢定者:任意張;同地點隊友:≤1)。見 [docs/05 §2.1](../docs/05-rules-engine-spec.md) —— 這是**多人同步屏障**:伺服器對每位可投入者各發一則,收齊回應才抽標記 |
| `CHOOSE_TARGET` | `{ candidates[], min, max }` | 選擇目標 |
| `CHOOSE_OPTION` | `{ prompt, options[] }` | 二擇一 / 多擇一 |

`CHOICE_RESPONSE.choice` 依 kind:
- `COMMIT_CARDS` → `{ committedCardIds: string[] }`
- `CHOOSE_TARGET` → `{ targetIds: string[] }`
- `CHOOSE_OPTION` → `{ optionId: string }`

## GameStateView(伺服器 → 客戶端的過濾視圖)

```jsonc
{
  "round": 2,
  "phase": "INVESTIGATION",          // MYTHOS|INVESTIGATION|ENEMY|UPKEEP
  "activeInvestigatorId": "joe_diamond",
  "you": {                            // 只有「你」看得到自己的手牌
    "investigatorId": "joe_diamond",
    "skills": { "willpower":2, "intellect":4, "combat":3, "agility":3 },
    "health": 7, "damage": 1, "sanity": 7, "horror": 1,
    "resources": 5, "cluesHeld": 0, "actionsRemaining": 3,
    "locationId": "dormitories",
    "hand": [ { "cardId":"c1", "name":"Vicious Blow", "skillIcons":["COMBAT"] } ],
    "engagedEnemyIds": ["e1"]
  },
  "otherInvestigators": [             // 他人:不含手牌內容
    { "investigatorId":"daniela", "locationId":"dormitories", "damage":0, "horror":0, "handCount":5 }
  ],
  "locations": [
    { "id":"dormitories", "name":"Dormitories", "revealed":true, "shroud":2, "clues":1,
      "connections":["friends_room","quad"], "enemyIds":["e1"] }
  ],
  "enemies": [
    { "id":"e1", "name":"Servant of Flame", "fight":4, "health":5, "damageOn":0,
      "evade":2, "keywords":["HUNTER","RETALIATE"], "engagedWith":"joe_diamond", "exhausted":false }
  ],
  "act":    { "name":"Where There's Smoke…", "cluesSpent":0, "threshold":2 },
  "agenda": { "name":"Past Curfew", "doom":1, "threshold":5 },
  "chaosBagSummary": { "total":16 }   // 內容公開,順序無關(袋子是隨機抽)
}
```

> 注意:`chaosBag` 的**實際抽取在伺服器**(seedable RNG),客戶端只知道袋內組成、不能預測抽到什麼。遭遇牌堆順序完全不下發。

## 一次技能檢定的訊息序列(範例:戰鬥 + 隊友協助)

```
Client(Joe) →  INTENT { action:"FIGHT", payload:{ enemyId:"e1" } }
Server      →  CHOICE_REQUEST { requestId:"r1", kind:"COMMIT_CARDS",
                 options:{ skill:"COMBAT", base:3, difficulty:4, eligibleCards:[...], maxCommit:99 } }   // 給 Joe
Server      →  CHOICE_REQUEST { requestId:"r2", kind:"COMMIT_CARDS",
                 options:{ ..., maxCommit:1 } }                                                          // 給同地點的 Daniela
Client(Joe)     →  CHOICE_RESPONSE { requestId:"r1", choice:{ committedCardIds:["c2"] } }
Client(Daniela) →  CHOICE_RESPONSE { requestId:"r2", choice:{ committedCardIds:["c9"] } }
Server      →  EVENT { message:"投入 制伏(+2)、Daniela 兇狠打擊(+1) → 技能 6" }
Server      →  EVENT { message:"抽到混沌標記 -1;6-1=5 ≥ 4 成功" }
Server      →  STATE { view: … }   // 廣播更新後狀態給所有客戶端
```

伺服器把 r1/r2 送給各自客戶端;**回應以到達順序處理(先丟先算,無固定先後)**,收齊(或超時/略過)才進行抽標記 —— 這一步是**屏障**,見 [docs/05 §2.1](../docs/05-rules-engine-spec.md)、[§4.1](../docs/05-rules-engine-spec.md)。
