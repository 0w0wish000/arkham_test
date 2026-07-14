# 09 · 大廳、存檔與交握(LAN 熟人多人)

> 取代 `room` 的**存檔驅動(save-driven)**進入流程。以區域網路熟人同樂為前提,不處理外網 / 惡意玩家。
> 承接 [05 規則引擎](05-rules-engine-spec.md)、[07 LAN 連線](07-lan-setup.md)、[08 存檔與朔源](08-save-and-provenance.md)。

## 0. 一句話
**廢除 room。** 用「**玩家身分(playerId)** + **存檔(campaign)**」當串聯核心:大家連進主機 → 看見「進行中桌次」與「本機存檔」→ 點同一個存檔就湊在一起。進出、載入、名冊、難度全部掛在**存檔**上。

---

## 1. 三種身分,務必分清楚

| 身分 | 是什麼 | 存哪 | 生命週期 |
|---|---|---|---|
| **playerId(玩家)** | 「人」的識別。首次輸入 ID,之後記住,可改名。 | 各玩家**本機** profile | 跨所有存檔、永久 |
| **investigator(調查員)** | 某存檔裡「這個人選的角色」。 | 存檔的名冊內 | 綁一個存檔;死亡後永久封鎖 |
| **campaign save(存檔)** | 一條戰役路線 + 名冊 + 進度 + 快照。`campaignId` 是串聯鑰匙。 | 主機(權威)+ 複製到每人本機 | 一整條戰役 |

> 例:playerId=`bill` 在存檔「週五團」帶 `roland_banks`;下週開新存檔「假日團」可改帶別的角色。`bill` 恆定,角色隨存檔。

---

## 2. 玩家身分(ID)

- **首次啟動**:彈窗要求輸入顯示名稱 → 產生 `playerId`(UUID)+ `displayName`,寫入本機 profile(`localStorage`)。
- **之後**:自動帶入,不再詢問。
- **改名**:設定裡可改 `displayName`(`playerId` 不變,記錄不斷)。
- **有無記錄**:連上主機時送 `HELLO { playerId }`;主機用 `playerId` 去**存檔名冊**比對此人參與過哪些戰役(用於「加載存檔」清單標示「你在此檔中帶 XXX」)。

---

## 3. 取代 room:主機桌次 + 存檔 campaignId

「串聯」拆成兩件事,都**不必打字對暗號**:

1. **連到同一台主機**(輸入主機 IP,或區網自動探索 —— 見 §14)。
2. **點同一個存檔**:畫面同時列出
   - **進行中桌次**(server 發布:目前這台主機上開著的 session)
   - **我的本機存檔**(client 讀自己 `localStorage` 的存檔;可據此「開桌載入」)

`campaignId` 是比對鑰匙:某人用本機存檔開桌 → server 以該 `campaignId` 建立 session → 其他持有**同 `campaignId`** 存檔的人,清單上該檔會標「🟢 進行中,可加入」,點下去就併入同一桌。

> 所以:**新開檔** = 在主機建一張新桌;**載入檔** = 用某人的本機存檔在主機開一張桌。其他人一律「JOIN 這張桌」。room 消失,換成「桌次/存檔」。

---

## 4. 主選單(state machine)

```
              ┌─────────────┐   首次無 profile
   啟動 ─────▶│ 輸入身分 ID  │───────────────┐
              └─────────────┘               │ 已有 profile
                                            ▼
                                   ┌──────────────────┐
                                   │     主選單        │
                                   │ 開新檔案          │
                                   │ 加載存檔          │
                                   │ 離開              │
                                   └───────┬──────────┘
                    開新檔案 │             │ 加載存檔
                            ▼             ▼
             ┌───────────────────┐   ┌───────────────────────┐
             │ 選難度 + 選角色     │   │ 選一個存檔(本機/桌次)  │
             │  → 初始牌組構築     │   └───────────┬───────────┘
             └─────────┬─────────┘         依存檔 stage 分流
                       │                   ┌───────┴────────┐
                       │             DECKBUILDING      IN_SCENARIO
                       │                   │                │
                       ▼                   ▼                ▼
              ┌───────────────────┐  ┌──────────────┐  ┌──────────────┐
              │  牌組大廳(屏障B) │  │ 牌組大廳(B) │  │ 戰役載入(A) │
              │  等大家牌組完成    │  │ 載入舊牌組    │  │ 載入快照      │
              └─────────┬─────────┘  └──────┬───────┘  └──────┬───────┘
                        └──────────┬────────┘                 │
                                   ▼                          ▼
                            ┌─────────────┐            ┌──────────────┐
                            │  進入戰役    │◀───────────│  面板 + log   │
                            │(初始 STATE) │            │  彈窗(續玩)  │
                            └─────────────┘            └──────────────┘
```

---

## 5. 存檔資料模型

```jsonc
CampaignSave {
  "campaignId": "uuid",          // 串聯鑰匙
  "name": "週五團",               // 人可讀桌名
  "campaignKey": "core",         // 哪條主線(核心 / 敦威治 / …)
  "difficulty": "STANDARD",      // 基準難度(玩家選;EASY|STANDARD|HARD|EXPERT)
  "stage": "DECKBUILDING",       // DECKBUILDING | IN_SCENARIO
  "progress": {
    "currentChapter": 2,         // 目前第幾章
    "path": [                    // 已完成章節與分歧(決定經驗上限、朔源)
      { "chapter": 1, "resolution": "R2", "maxXpAward": 5 }
    ]
  },
  "roster": [ Member, … ],       // 名冊(見下)
  "deadInvestigators": ["jenny_barnes"],  // 此存檔永久封鎖的角色
  "eventLog": [ … ],             // 出牌/事件文本(載入時彈窗回放)
  "snapshot": GameState | null,  // stage=IN_SCENARIO 時的完整快照(見 08)
  "savedAt": "ISO", "savedBy": "playerId", "version": 7   // 每次存檔 +1(朔源)
}

Member {
  "playerId": "bill",
  "displayName": "Bill",
  "investigatorId": "roland_banks",   // 此存檔的角色;非死亡不可改(§10)
  "deck": ["01006","01007", …],       // 牌組(牌組階段可編輯)
  "xp": 5,                            // 可用經驗(≤ 上限,§11)
  "trauma": { "physical": 1, "mental": 0 },  // 創傷跨章保留 → 影響起始上限
  "status": "ACTIVE"                  // ACTIVE(本章參戰)| SITTING_OUT(這章不打)| DEAD
}
```

> **存檔權威在主機、複製到每人本機**:任何寫檔(開新檔、名冊變動、章節推進、回合結算、保存離開)一律**主機先落地**,再 `SAVE_SNAPSHOT` 推到各 client 本機當離線備份(承 08 §6.5)。→ 主機可輪流,誰有最新 `version` 誰就能開桌載入。

---

## 6. 開新檔案

```
發起者 → CREATE_CAMPAIGN { name, campaignKey, difficulty }
主機   → 建 session(stage=DECKBUILDING,新 campaignId,roster=[發起者])
主機   → ENTER_DECKBUILD { campaign, you: 新Member(空牌組) }
主機   → 廣播 SESSION_ROSTER(旁白:「Bill 開了新的調查:週五團」)
其他人 → JOIN_SESSION { campaignId }   // 從「進行中桌次」點進來
主機   → ENTER_DECKBUILD …;廣播 SESSION_ROSTER(旁白:「Daniela 加入調查」)
```
- 每人:`PICK_INVESTIGATOR { investigatorId }` → `SET_DECK { deck, xp }` → 進入**牌組大廳屏障(§8.2)**。
- 新檔起始經驗依戰役規則(核心通常 0);難度基準已選,實際縮放看**開打時參戰人數**(§12)。

---

## 7. 加載存檔(兩情境)

發起載入者提供存檔(本機那份);主機依 `stage` 分流。**誰選擇載入,誰就先進到 waiting**,其他人陸續 ready。

### 情境 A — 上次停在戰役中(`stage=IN_SCENARIO`)
```
發起者 → OFFER_SAVE { save }            // 或該桌已開 → JOIN_SESSION
主機   → 建/取 session(stage=IN_SCENARIO),存 snapshot
主機   → ENTER_SCENARIO_LOADING { campaign, roster, eventLog }   // 給發起者:先進 waiting
發起者 → RESUME { state: save.snapshot }   // 由這位「host 載入者」把快照送回重建(見 08)
其他人 → JOIN_SESSION → ENTER_SCENARIO_LOADING → READY_LOAD { ready:true }
        ……【屏障 A:等當下連進來的 ACTIVE 成員都 ready(主機可強制)】……
主機   → STATE(對局啟用)+ LOG_HISTORY(彈窗回放 eventLog)
```

### 情境 B — 上次停在牌組編輯(`stage=DECKBUILDING`)
> 補充需求:**牌組編輯也能「保存退出」**(打完一章想休息)。

```
發起者 → OFFER_SAVE { save }
主機   → 建/取 session(stage=DECKBUILDING)
主機   → ENTER_DECKBUILD { campaign, you: 你上次的 Member(帶回舊牌組) }
其他人 → JOIN_SESSION → ENTER_DECKBUILD(各自帶回舊牌組)
        此處可調整:戰役進度、經驗(§11)、牌組
        不可調整:調查員本人、角色必帶卡(§10)
        ……【屏障 B:等大家牌組完成 → 進入戰役】……
```

---

## 8. 交握 / 屏障(核心)

兩個屏障,規則相同:**等「當下連進這桌的所有 `ACTIVE` 成員」按 ready;主機有「強制開始」鈕**應付臨時掛機。缺席的 ACTIVE 成員,其調查員仍在狀態中,待他重連**接手**(§9 亂入)。

### 8.1 屏障 A — 戰役載入
```
成員逐一:ENTER_SCENARIO_LOADING → READY_LOAD{true}
主機持續:SESSION_ROSTER { members:[{name, ready}], readyCount, total, canForce }
觸發:readyCount == 連線ACTIVE數(或主機按 FORCE_START)
→ 主機廣播 STATE(啟用)+ LOG_HISTORY
```

### 8.2 屏障 B — 牌組完成
```
成員逐一:SET_DECK → READY_DECK{true}(可再 READY_DECK{false} 反悔)
主機持續:SESSION_ROSTER { …, readyCount, total }
觸發:所有 ACTIVE 成員 ready(或 FORCE_START)
→ 主機 START_SCENARIO:用各人牌組 + 參戰人數 建初始 GameState → ENTER_SCENARIO{view}
```

### 8.3 旁白事件(文本推導)
名冊/屏障變動一律發**敘事式 EVENT**(沿用 05 的事件播報),讓畫面有字幕感:
| 情況 | 文本 |
|---|---|
| 加入桌次 | 「**Daniela** 加入了調查。」 |
| 暫時脫離 | 「**Roland** 暫時脫離調查隊伍。」 |
| 準備完成 | 「**Bill** 已整裝待發。」(ready) |
| 全員就緒 | 「隊伍準備完畢 —— 踏入 **敦威治村**。」 |
| 載入中 | 「正在喚回上次的記憶……(2/3 已就緒)」 |
| 調查員死亡 | 「**Jenny** 再也沒有回來。」(§10) |

---

## 9. 動態名冊:中離、亂入、跨章跳動

以**章節邊界(牌組大廳)**為重配時機:

- **中離(sit-out)**:`SIT_OUT` → status=`SITTING_OUT`,不進本章戰役;旁白「OOO 暫時脫離調查」。下章牌組大廳可再 `ACTIVE` 歸隊。
- **亂入(join-in)**:新的 `playerId` `JOIN_SESSION` → 若名冊沒他 → 建新 Member、`PICK_INVESTIGATOR`(不得選 `deadInvestigators`)→ 併入本章。
- **接手(takeover)**:**戰役進行中只有「原玩家(同 `playerId`)」能接手**自己掉線的調查員 —— 重連後**直接重打他掉線的那一個動作**(server 保留他掉線時的待決狀態:若卡在技能檢定投入屏障,重連即重新面對同一個投入決策;若尚未送出的意圖,回到該調查員可行動的狀態)。**戰役中不接受新玩家亂入**,亂入只在章節邊界(牌組大廳)發生。
- **跨章跳動**:每次 `START_SCENARIO` 都寫一份**新的存檔 version**(immutable checkpoint,承 08 朔源)。所以「這章某人不打、下次再一起打」= 下個章節大廳把他 status 轉回 ACTIVE 即可,歷史不被覆蓋。
- **難度隨人數**:見 §12。

> 設計取捨:名冊只在**牌組大廳/章節邊界**開放增刪(戰役進行中僅允許「接手」),避免半場改人數破壞規則結算。

---

## 10. 調查員死亡 → 投票換新角色

- 本遊戲**創傷(trauma)跨章保留**;當調查員被擊敗至死亡(創傷達上限或劇情擊殺)→ status=`DEAD`。
- 觸發投票(可在死亡當下或下個牌組大廳):
```
任一人 → PROPOSE_NEW_CHARACTER { playerId }
主機   → VOTE_PROMPT { requestId, subject, reason:"Jenny 已死亡,是否讓 Bill 改帶新角色?" }
全員   → VOTE { requestId, yes }
主機   → 計票(門檻:過半 / 全同意,見下)
   過 → 把該 investigatorId 加入 deadInvestigators(永久封鎖)
        → 該玩家 PICK_INVESTIGATOR(排除 deadInvestigators)→ 新牌組(XP 比照 §11)
   不過 → 該玩家 SITTING_OUT(可觀戰)
```
- **不與「不可改角色」衝突**:平時牌組大廳鎖角色;**死亡是唯一解鎖**,且**死者永久不可重用**(`deadInvestigators`)。
- 門檻建議:**過半同意**(熟人取信任;可設定成全同意)。

---

## 11. 經驗編輯與上限

- 牌組大廳可**自改 XP**(戰役會影響,允許手動校正)。
- **硬上限**:`xp ≤ maxXpToDate`,其中
  `maxXpToDate = Σ(progress.path[*].maxXpAward)` —— 該存檔戰役路線**至今理論可取得的最大經驗**。
- server 於 `SET_DECK` 驗證:超過 → 回 `ERROR「經驗超過此戰役可取得上限 N」`,不套用。
- 「調整經驗」獨立功能同一把尺(同一上限)。
- 新角色 / 死亡換角的 XP 亦受同上限約束。

---

## 12. 難度隨人數

- **基準難度**(EASY…EXPERT):開新檔時選,寫在存檔;影響混沌袋組成 / 起始資源等常數。
- **人數縮放**:場景設置量(線索 = clueValue × 參戰人數、遭遇抽牌 = 每人一張……)以 **`START_SCENARIO` 當下的 ACTIVE ready 人數**為準(引擎 `ScenarioFactory` 已是這模型)。
- 因**中離/亂入**造成人數變動 → 下一章 `START_SCENARIO` 自動用新人數縮放。**同一章進行中人數鎖定**(僅允許接手,不改縮放)。

---

## 13. 協定訊息(新增,對齊 protocol.md)

### Client → Server
| type | 欄位 | 說明 |
|---|---|---|
| `HELLO` | `playerId, displayName` | 連上後自報身分;主機回 `LOBBY` |
| `CREATE_CAMPAIGN` | `name, campaignKey, difficulty` | 開新檔案 → 建桌(DECKBUILDING) |
| `OFFER_SAVE` | `save` | 用本機存檔開桌(載入);主機依 stage 分流 |
| `JOIN_SESSION` | `campaignId` | 加入進行中桌次 |
| `LEAVE_SESSION` | — | 回主選單 |
| `PICK_INVESTIGATOR` | `investigatorId` | 選/換角色(新檔、亂入、死亡換角) |
| `SET_DECK` | `deck, xp` | 提交/更新牌組(server 驗 XP 上限) |
| `READY_DECK` | `ready` | 牌組完成/反悔(屏障 B) |
| `SIT_OUT` | `ready?` | 本章中離 / 歸隊 |
| `READY_LOAD` | `ready` | 我已載入戰役快照(屏障 A) |
| `RESUME` | `state` | 由載入發起者送回快照重建(已存在,見 08) |
| `FORCE_START` | — | (主機)強制越過屏障 |
| `PROPOSE_NEW_CHARACTER` | `playerId` | 對死亡者發起換角投票 |
| `VOTE` | `requestId, yes` | 投票(換角 / 沿用 SAVE_VOTE 家族) |

### Server → Client
| type | 欄位 | 說明 |
|---|---|---|
| `IDENTITY_NEEDED` | — | 本機無 profile → 前端彈窗要 ID |
| `LOBBY` | `activeSessions[]` | 進行中桌次清單(前端再併本機存檔清單) |
| `ENTER_DECKBUILD` | `campaign, you(Member)` | 進牌組編輯(帶回舊牌組) |
| `ENTER_SCENARIO_LOADING` | `campaign, roster, eventLog` | 進戰役 waiting 畫面(屏障 A) |
| `SESSION_ROSTER` | `members[], readyCount, total, canForce, stage` | 名冊 + 屏障進度(驅動 waiting UI) |
| `ENTER_SCENARIO` | `view` | 屏障通過 → 初始 STATE |
| `LOG_HISTORY` | `entries[]` | 載入後彈窗回放的事件文本 |
| `VOTE_PROMPT` | `requestId, subject, reason` | 換角投票彈窗 |
| `SAVE_SNAPSHOT` | `…` | 存檔複製到各本機(已存在,見 08) |
| `EVENT` | `event, message` | 旁白事件(§8.3;已存在) |
| `ERROR` | `message` | 例:XP 超上限、選到封鎖角色 |

---

## 14. LAN 邊界情況(熟人前提)

- **主機探索**:先支援「輸入主機 IP」;之後可加 UDP 廣播 / mDNS 自動找桌(區網),連 IP 都不用打。
- **掉線接手**:戰役中掉線 → 調查員狀態留 server;**僅原玩家(同 `playerId`)**重連自動接回,並重打掉線的那一動(§9);戰役中不接受他人頂替或亂入。
- **主機掛掉**:因存檔已複製到每人本機,換人當主機 → 用最新 `version` `OFFER_SAVE` 重開桌即可(§3、§5)。
- **版本衝突**:兩人各有不同 `version` 的同 `campaignId` 存檔 → 主機取 `version` 較大者;提示較舊者將被覆蓋。
- **信任模型**:不驗合法性(承既有設計),但 **XP 上限 / 死亡封鎖 / 角色鎖**這類「防手滑、保campaign一致」的約束仍由 server 把關。

---

## 15. 分期實作建議

| 階段 | 內容 | 產出可玩性 |
|---|---|---|
| ✅ **P1 身分 + 大廳骨架** | `playerId` profile、`HELLO/LOBBY`、主選單、`CREATE_CAMPAIGN`/`JOIN_SESSION`、`SESSION_ROSTER` | 能輸入 ID、看桌、湊人(取代 room) |
| ✅ **P2 牌組大廳 + 屏障 B** | 選角(唯一性)、`SET_DECK`、`READY_DECK`、`FORCE_START`、`START_SCENARIO`;**完整選卡器**嵌入(iframe + `postMessage` 橋接) | 一起選角/建牌 → 開打 → 一起玩 |
| ✅ **P3 戰役載入 + 屏障 A** | 戰役級存檔(名冊+牌組+快照+eventLog)→ `CAMPAIGN_SNAPSHOT` 複製各本機;`OFFER_SAVE` 依 stage 還原(DECKBUILDING 還原牌組 / IN_SCENARIO→`LOADING`)→ `READY_LOAD` 屏障A → 重建對局 → `LOG_HISTORY` 回放 | 續玩到一半的戰役 / 牌組 |
| ✅ **P4 動態名冊** | `SIT_OUT`(中離/歸隊、不擋屏障)、亂入(大廳 JOIN)、**接手**(戰役中掉線保留名冊+session,原玩家重連 `JOIN_SESSION`→`reattach` 送 STATE + 補發卡住的投入決策=重打掉線那一動)、難度隨參戰人數、旁白事件。**跨章 version / 打完接下一章**待多劇本引擎(暫緩) | 中離/亂入/接手 |
| ✅ **P5 死亡換角 + 投票** | `PROPOSE_NEW_CHARACTER`→`VOTE_PROMPT`→`VOTE`(過半通過)→ 死者角色加入 `deadInvestigators` 永久封鎖、該玩家可改選;`PICK_INVESTIGATOR` 擋封鎖角色;`SET_DECK` 擋 `xp > maxXp`(存檔含 deadInvestigators + maxXp) | 完整戰役生命週期 |

> **docs/09 全數(P1–P5)已實作完成。** e2e:`lobby-e2e`(14)、`deckbuild-e2e`(13)、`save-reload-e2e`(18)、`dynamic-roster-e2e`(9,中離+難度縮放+接手+重打掉線那一動)、`char-vote-e2e`(7,死亡換角+封鎖+XP 上限)、`protocol-e2e`(32)。全部由 `node e2e/run.mjs` 一次跑完(自帶暫時 server、自動收尾)。
>
> **未竟(需多劇本引擎)**:跨章 version / 打完一章接下一章的戰役推進、完整能力時機系統、全卡實作、戰役劇情文本(FFG 版權)。XP 上限的 `maxXp` 目前為常數(50);正式版應依戰役路線 `Σ maxXpAward` 計算。
>
> **接手 vs 重載**:戰役中有人掉線但**還有人在** → 保留 session,原玩家重連即接手;**全員離線** → 清桌,之後任一人用本機存檔 `OFFER_SAVE` 從頭重載(屏障 A)。
>
> **P2-2 選卡器整合**:`client/public/deckbuilder.html`(由 `prototype/deckbuilder.html` 複製 + 加橋接;之後應合併為單一來源)。牌組大廳「🃏 完整選卡器」開 iframe(`?embed=1&inv=&lockInv=`),「✅ 完成牌組」`postMessage {investigatorId,deck,xp}` → 大廳送 `PICK_INVESTIGATOR + SET_DECK + READY_DECK`。目前 lite 引擎僅用起手技能牌做投入,牌組內容先記錄不深度影響對局(完整抽牌機制後續)。XP 上限(§11)、SIT_OUT 併入 P4。
