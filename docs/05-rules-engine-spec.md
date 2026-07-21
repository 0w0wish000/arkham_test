# 05 · 規則引擎規格(取自官方規則書)

> 本文把 `rulebook/ahc100_rulebook-web.pdf`(**Revised Core Set**,© 2026 FFG)的規則,蒸餾成**引擎實作用的規格**。頁碼指向該 PDF。這是 [02-design-spec.md](02-design-spec.md) 規則引擎的「行為合約」。
>
> **權威來源分層(引擎必須遵守的優先序):**
> 1. **卡牌文字(Golden Rule, p.32)**:卡片文字 > 規則書。
> 2. **Arkham Grimoire(線上活規則, p.32)**:比規則書更完整的裁決總集,隨新卡更新。**引擎的裁決邏輯應以 Grimoire 為準,規則書為入門。**
> 3. **Silver Rule(p.32)**:卡片衝突時,遭遇卡 > 玩家卡;同類則由**首席調查員(lead)**決定。
> 4. **Grim Rule(p.32)**:無法裁決/取得共識時,採「對玩家最差的合理結果」,由 lead 拍板,繼續遊戲。→ **引擎遇到無法自動裁決的時機衝突時的 fallback**。
> 5. **Cannot 為絕對(p.33, 38)**:含「cannot」的效果不可被覆蓋。

---

## 1. 回合/階段序列(p.11, 17, 18, 48)

```
Round:
  1. Mythos Phase        ← 第一輪跳過(p.11)
       a. 當前 agenda +1 doom
       b. 比對「場上總 doom(agenda + 各卡)」≥ agenda 門檻 → 推進 agenda(step 2)
       c. 依 player order,每位調查員抽 1 遭遇卡並結算
  2. Investigation Phase
       - **全自由交錯**:每位調查員各有 3 個行動,任一位只要仍有剩餘行動即可行動,不必等別人打完;團隊可任意穿插(見 §4.1)
  3. Enemy Phase
       a. Enemies move:ready 且 unengaged 的 hunter / patrol 移動 1 步
       b. Enemies attack:ready 且 engaged 的敵人各攻擊 1 次(damage + horror 同時)
  4. Upkeep Phase
       a. 各調查員 investigator token 翻回正面
       b. Ready 所有 exhausted 卡
       c. 與調查員同地點的 unengaged 敵人 → engage(p.19)
       d. 各調查員抽 1 張 + 獲得 1 資源
       e. 手牌 > 8 → 棄至 8
```
> **🔧 引擎:** 一台明確的階段/步驟狀態機。每個 step 邊界潛在開啟 player window(見 §3)。`agenda 推進`要移除**場上所有** doom(含 enemies/locations 上的),p.18。

---

## 2. 技能檢定 8 步驟(核心迴圈, p.15–16)

```
Step 1: 決定要檢定的技能(意/智/戰/敏)。檢定開始。
        ── [Player Window] ──
Step 2: 從手牌投入(commit)卡。
        ── [Player Window] ──
Step 3: 揭示混沌標記(可能不只 1 個)。
Step 4: 結算混沌符號標記效果(💀🌜📜🐙→劇本參考卡;⭐→調查員;⊗→見下)。
Step 5: 計算調查員修正後技能值。
Step 6: 判定成功/失敗(修正值 ≥ 難度 → 成功)。
Step 7: 套用檢定結果。
Step 8: 檢定結束(棄掉投入的卡,揭示的標記回袋)。
```
- **投入規則(p.15, 33):** 圖示相符 +1;wild(⚡)+1 任意;投入**不付資源**;**對「別的調查員」的檢定最多投入 1 張**;peril 檢定他人不可投入。
- **⊗ Autofail(p.15, 42):** 該檢定的修正後技能值視為 **0**(對「每失敗 1 點造成 X」的詭計特別關鍵)。
- **兩個 Player Window(p.16):** 分別在「決定技能後」與「投入後、揭示前」;可觸發 free(⟿)能力改值。

> **🔧 引擎:** SkillTest 子狀態機,每步可被能力介入。RNG(抽標記)必須 **server 端、seedable**(全遊戲隨機源集中)。

### 2.1 投入判定 = 第一個「多人同步屏障」(隊友協助)

Step 2「投入」看似單人,實則是**多台客戶端要同步做決定**的點,是本專案第一個真正的多人難點。

- **誰能投入(p.15, 33):**
  - 進行檢定者:從手牌投入**任意張**相符/百搭圖示的卡。
  - **同一地點**的其他調查員:**每人至多 1 張**相符/百搭圖示的卡。
  - 投入**不付費用**;**只有相符/百搭圖示計入**(一張 [戰][智] 投到戰鬥檢定只 +1)。
  - **Peril:** 抽到 peril 遭遇卡結算時,他人**不可**投入/協助。
  - 卡本身可能有限制(如「限投入自己的檢定」「限某類檢定」)。
- **權威伺服器怎麼處理(對應 [02](02-design-spec.md) §3.4 決策請求協定):**
  1. 進入 Step 2,伺服器算出「可投入者集合」= 檢定者 + 同地點其他調查員(peril 則僅檢定者)。
  2. 對**每一位**發 `ChoiceRequest`(檢定者:任意張;他人:≤1 張),各附其合法手牌。
  3. 這是一個**屏障(barrier)**:收齊所有人的「投入/略過」前,**不進 Step 3 抽標記**。
  4. 伺服器逐張驗證(圖示相符、在同地點、他人 ≤1、非 peril、卡片限制),每個相符/百搭圖示 +1。
- **客戶端畫面:**
  - 檢定者:顯示整手牌,可投入多張,即時看到「投入後技能值 / 難度」。
  - 同地點隊友:跳出「隊友 X 正在檢定,要投入 1 張幫忙嗎?」+ 其合法卡 + 「略過」。
  - 不同地點者:唯讀旁觀(「不在同一地點,無法協助」)。
  - 全員看到共享的「投入區 + 目前技能值」。
- **時機細節:** Step 2 前後各有 player window,可觸發 free(⟿)能力;**投入無強制順序 —— 開窗給所有可投入者,先丟先算(first-come-first-served)**;檢定者可多張、每位隊友 ≤1;送出即鎖定;全員確認/略過後關窗;若能力改變合法性需重新驗證。

> ✅ **原型已示範此畫面**(`prototype/`):基礎戰鬥 3 → 投入「制伏(+2)」+ 同地點隊友「兇狠打擊(+1)」→ 技能值 6;抽到 −1 → 6−1=5 ≥ 4 **成功**(若無協助則 3−1=2 **失敗**)。不相符圖示的卡(如 [智]、隊友的 [意])會被標為不可投入。

---

## 3. 能力分類與時機(p.38, 41, 44, 45)

### 3.1 能力型別
| 型別 | 標記 | 觸發 | 引擎行為 |
|---|---|---|---|
| **Constant 常駐** | — | 在場即持續作用;`during`/`while` 條件 | 不在某點觸發;持續套用修正/取代 |
| **Forced 強制** | `Forced –` | 指定時機(when/after/if/at)| **必觸發**(若能影響目標);**優先於 reaction**(p.41) |
| **Revelation 揭示** | `Revelation –` | 抽到遭遇卡/弱點當下(在 limbo)| 立即結算 |
| **Triggered 觸發** | ⟿ / ↺ / ➤ | 見下 | 需符合前置條件、可付代價 |
| **Keyword 關鍵字** | — | 各自定義(§5)| 「may」=可選,否則強制 |
| **Enemy instructions** | — | spawn 等 | 敵人進場行為 |

### 3.2 三種 Triggered(p.45, 48)
- **Free ⟿(free triggered):** 不花行動,可在**任何 player window** 觸發。
- **Reaction ↺(reaction triggered):** 有特定觸發條件;`when` = 觸發點達成、影響**結算前**;`after` = 影響**結算後**;每次觸發限用一次。
- **Action ➤(action triggered):** 花行動(可能 >1)啟動。

### 3.3 時機裁決規則
- **Forced 先於 Reaction**(同一時機點, p.41)。
- **Player Window** 是玩家可介入的暫停點(檢定內 2 個;流程各邊界亦有)。Setup 期間**無** action window(p.20)。
- **Cannot 絕對**;衝突用 Golden/Silver/Grim(見頂部)。
- **Target(p.45):** 能力需有合法目標才能發動;「choose」多目標同時選定;目標狀態若不會被效果改變則不可選(如已 exhausted 的敵人不可被「choose and exhaust」選)。

> **🔧 引擎:** 事件匯流排(每動作 pre/post 發布)+ 觸發登記表 + 效果堆疊。Player window 用「引擎暫停 → 對玩家發 ChoiceRequest → 等回應」實作(見 [02](02-design-spec.md) §3.4)。

---

## 4. 行動與趁隙攻擊(p.11–14, 38, 48)

**11 種行動:** Draw、Resource、Activate(➤)、Play、Move、Investigate、Engage、Evade、Fight、Parley、Resign。
- **Investigate:** 智力 vs 地點 shroud,成功取 1 clue。
- **Fight:** 戰鬥 vs 敵人 fight,成功造成傷害(**預設 1 點**, p.33)。
- **Evade:** 敏捷 vs 敵人 evade,成功使敵人 exhaust 並脫離交戰。
- **Parley / Resign:** 由卡片 ability 觸發,**不引發趁隙攻擊**(p.14)。
- **Play:** 付資源費;asset 進檯面(佔 slot);event 結算後入棄牌;skill 不能用 play(只能 commit)。

**趁隙攻擊(Attack of Opportunity, p.14, 38):** 與 ready 敵人交戰時,執行 **Fight/Evade/Parley/Resign 以外**的行動 → 每個交戰敵人各攻擊 1 次。一個行動每敵人只觸發 1 次;敵人 AoO 不 exhaust;由觸發者決定多敵人結算順序。

**Equipment Slots(p.12):** accessory ×1、head ×1、body ×1、ally ×1、hand ×2、arcane ×2。超額須棄置。

### 4.1 玩家順序:合作、全自由交錯(設計決定)

Arkham 是**合作**遊戲,順序刻意保持彈性,**不強制固定排序**。本電子版更進一步採**全自由交錯**(與官方 p.11「一人一完整回合、不可交錯」的**刻意差異**,為提升合作互動樂趣):

- **調查階段行動順序 = 全自由交錯:** 每位調查員各有 3 個行動;**任一位只要仍有剩餘行動即可行動,不必等別人打完**,團隊可任意穿插(例:A 動兩次 → B 動一次 → A 再一次 → B 兩次…),也不規定誰先打什麼牌。引擎/大廳只追蹤「各調查員本輪剩餘行動數」,任一有剩餘者皆可接手。
- **投入協助 = 先丟先算(first-come-first-served):** 檢定的投入窗對所有可投入者**同時開啟**,**誰先送出就先算**,無固定先後;每位隊友 ≤1、檢定者任意張。
- **「in player order」只用於「必做且同時發生」的步驟:** 例如神話階段每人各抽 1 張遭遇卡(§1.1c)、多個敵人同時攻擊的結算。此時才採**座位順序(自 lead 起順時針)**、平手由 lead 定。這只是給「同時發生的必做事」一個確定順序,**不約束玩家自由的行動順序**。

> **🔧 引擎/伺服器:** 不要硬編固定回合順序,也**不要用單一 `activeInvestigatorId` 鎖住整個回合**。改為**每位調查員各自追蹤剩餘行動數**,任一位有剩餘行動的調查員送來的行動皆可受理(全自由交錯);commit 窗以**到達順序**處理、驗證後即鎖定。只有 mandatory 同時步驟才套座位順序。

---

## 5. 關鍵字登記表(Keyword Registry, p.38–46, 48)

> 引擎需要一個關鍵字註冊表,每個關鍵字 = 一段掛在特定時機的行為。

| 關鍵字 | 時機 | 引擎行為(摘要) |
|---|---|---|
| **Alert** | 閃避 ready alert 敵人**失敗**後 | 該敵人攻擊閃避者(不論是否交戰) |
| **Aloof** | spawn / 交戰判定 | 不自動交戰;spawn 為 unengaged;未交戰不可被 fight/evade;需 engage 行動 |
| **Doomed** | 敵人被**擊敗**時 | 當前 agenda +1 doom(可推進);「discard」不算擊敗、不觸發 |
| **Elusive** | ready elusive 敵人攻擊或被攻擊後 | 脫離所有交戰、移到相鄰(盡量無調查員)地點、exhaust |
| **Exceptional** | 牌組構築 | XP 花費 ×2;同名限 1 張 |
| **Fast** | 打出時 | 不花行動、不引 AoO;fast asset 僅自己回合 player window;fast event 依其指示時機 |
| **Hidden** | 抽到 hidden 遭遇卡結算後 | 不棄置,秘密加入抽者手牌;在手中持續生效,依卡指示(通常花代價/滿足條件)才能移除 |
| **Hunter** | 敵人階段開始 | ready+unengaged 沿最短路徑向最近調查員移 1 步;平手→prey/lead;被擋不動 |
| **Massive** | 交戰/攻擊 | 與同地點**每位**調查員交戰;攻擊時逐一打各人;不 exhaust 直到全打完 |
| **Patrol** | 敵人階段開始 | ready+unengaged 向指定目標移 1 步;平手→lead |
| **Peril** | 抽到 peril 遭遇卡結算中 | 抽者不可與他人商議;他人不可介入其檢定 |
| **Permanent** | 牌組構築 | 不計牌組張數;開局在場;不可離場(除非 owner 被淘汰);不可被指定 attach |
| **Prey** | hunter/交戰目標判定 | 指定目標(如「Prey(highest 意志)」);「only」=只交戰該人 |
| **Retaliate** | 攻擊 ready retaliate 敵人**失敗**後 | 該敵人攻擊攻擊者(不論是否交戰);不 exhaust |
| **Seal** | 能力指示 | 從袋中取指定標記封在卡上;封印中不可被抽;離場則釋放回袋 |
| **Starting** | 開局(抽手牌+mulligan 後)| 從牌庫搜 1 張該關鍵字卡入起手 |
| **Surge** | 抽到 surge 遭遇卡結算後 | 再抽 1 張遭遇卡並結算(setup 也生效)|
| **Swarming X** | 敵人進場時 | 額外生成 X 張 swarm 卡疊附其下;各為獨立敵人共享主卡數值,擊敗才移除 |
| **Uses (X)** | 進場時 | 放 X 個指定型別 token(ammo/charge…);作為代價花用;可補至 X |
| **Victory X** | 敵人被擊敗 / 劇本結束 | 進 victory display(場外、共享)→ 劇本結束換 XP |

> **🔧 引擎:** 「may」關鍵字 = 可選;其餘強制(p.42)。同一敵人不可重複獲得同關鍵字。

---

## 6. 混沌標記(p.5, 15, 48)

- **數值:** `+1,0,−1,−2,−3,−4,−5,−6,−8`(依難度組合;Revised Core 標準袋為 16 顆, p.7)。
- **符號:** 💀 Skull、🌜 Cultist、📜 Tablet、🐙 Elder Thing → 查**劇本參考卡**結算;⭐ Elder Sign → 查**調查員**結算;⊗ Autofail → 技能值視為 0。
- **難度(Easy/Standard/Hard/Expert):** 不同標記集合;戰役中袋子跨劇本延續、可被卡片增刪。
- **Seal:** 見 §5;封印標記不在袋中。

---

## 7. 資源、代價、限制(p.40, 42, 45, 46)

- **Resource cost:** 從 resource pool 付到 token pool。
- **Ability cost(`cost: effect`):** 冒號前為代價(可含 exhaust/棄牌/受傷/花 use),須可全額支付且效果能影響目標才可發動。
- **Limit / Max(p.42):** `Limit X per [period]`、`Max X per [period]`、group limit(全隊共享);被取消的效果仍計入 limit。
- **Unique(✦, p.45):** 同名唯一卡同時最多 1 張在場。
- **Exhaust/Ready(p.42, 44):** exhaust=轉 90°;ready 才能再 exhaust。

---

## 8. 生命/理智、擊敗、淘汰、創傷(p.17, 22, 41, 44)

- **Damage→Health、Horror→Sanity;** 歸零(≥)即被擊敗。可分配到有 health/sanity 的 asset 吸收(direct damage/horror 必須指到調查員卡)。
- **敵人擊敗** → encounter 棄牌堆(有 Victory X → victory display)。
- **Elimination(p.41):** 調查員被擊敗/resign → 退出該劇本;其 clue 留在所在地點、resource 歸還、threat area 敵人留在地點;若是 lead 則重選;全員淘汰 → 劇本「no resolution」。
- **戰役創傷(p.22):** 因 damage 被擊敗 → 1 physical trauma(等於印刷 health → **killed**);因 horror → 1 mental trauma(等於印刷 sanity → **driven insane**);同時 → 玩家選;killed/insane/devoured → 該調查員退出整段戰役。

---

## 9. 卡牌解剖 → 資料模型欄位(p.34–37)

### Scenario cards(p.34–35)
`encounterSetSymbol, cardType, title, traits[], ability,`
`enemy{ fight, health, evade, damage, horror },`
`location{ shroud, clueValue, connectionIcons[] },`
`actAgenda{ sequence, clueThreshold, doomThreshold },`
`productSetInfo, encounterSetNumber`

### Player cards(p.36–37)
`cost, level(pips 0–5), cardType, classSymbol, title, subtitle,`
`traits[], ability, skillTestIcons[],`
`investigator{ skills{willpower,intellect,combat,agility}, elderSignAbility, health, sanity },`
`productSetInfo`

> 對應 [02-design-spec.md](02-design-spec.md) §5 的 `CardDefinition`。**Codex(📖, p.39):** 某些卡連結戰役指南的條目(codex entry),需把「戰役指南文字」也納入內容資料模型。

---

## 10. 「容易漏掉的規則」= 引擎測試案例(p.33, 39, 42)

這些正是**天真引擎最常做錯**的地方,應各寫一條情境測試:
1. **趁隙攻擊**:Fight/Evade/Parley/Resign 以外的行動在交戰下都會挨打(p.33)。
2. **戰鬥預設 1 傷**(p.33)。
3. **fast asset 仍限自己回合**(p.33, 41)。
4. **投入卡不付資源;對他人檢定最多投 1 張**(p.33)。
5. **「cannot」絕對**(p.33)。
6. **Forced 先於 reaction**(p.33, 41)。
7. **⊗ 使技能值歸 0**(p.33)。
8. **敵人預設 spawn 為 engaged**(除非有 spawn 指示或 aloof, p.33)。
9. **可對「他人 threat area 的 treachery」觸發 ➤ 能力**(p.33)。
10. **地點預設以未揭示面進場**(p.33)。
11. **agenda 推進移除場上所有 doom**(p.18)。
12. **Codex 條目每劇本每條僅結算一次**(除非另有說明, p.39)。
13. **Limbo**:event/treachery/skill 結算中的過渡態,不算在場(p.42)。

---

## 11. 內容範圍槓桿:Current vs Legacy 環境(p.31)

規則書明訂兩種環境,**直接影響「全內容」的實作策略**:
- **Current(當前)環境:** 較小、經策展的卡池,官方**推薦**玩法,戰役/劇本為此平衡。→ **建議數位版 MVP 先鎖定當前環境**(以 Revised Core Set 的 *Brethren of Ash* 起步)。
- **Legacy(遺產)環境:** 納入所有舊版/絕版,存在強力連段與詭異互動。→ 通用引擎的**終極壓力測試**,列為長期目標。

➡️ 內容分期策略見 [04-roadmap-risks.md](04-roadmap-risks.md);此環境概念是「全內容」野心的務實切分點。
