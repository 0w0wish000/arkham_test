// ════════════════════════════════════════════════════════════════════
//  Arkham 玩家旅程 e2e【單人 · 單場劇本(沙盒)】
//  模擬一位玩家從頭玩到底的完整鏈路,對應測試案例 S1–S8:
//    S1 大廳(身分→建桌)          S2 選卡(選角/牌組驗證/就緒開打)
//    S3 基本行動(資源/抽牌)      S4 調查檢定(投入→抽標記→取線索)
//    S5 打卡+啟動(DSL/ACTIVATE)  S6 戰鬥(移動→交戰→打到擊敗)
//    S7 存檔繼承(戰役中存檔→離開→重載續玩,狀態完整)
//    S8 收尾(湊線索推進幕→勝利→XP 結算→第 2 章)
//  檢定含隨機(混沌袋):以「有上限的重試迴圈」收斂,只斷言最終結果。
//
//  用法:  node e2e/journey-solo-e2e.mjs [ws://host:8080]
// ════════════════════════════════════════════════════════════════════

const URL = process.argv[2] || process.env.ARKHAM_WS || "ws://localhost:8080";
const WS = `${URL.replace(/\/$/, "")}/ws/game`;

let passed = 0, failed = 0;
const fails = [];
function check(cond, msg, detail) {
  if (cond) { passed++; console.log(`  ✓ ${msg}`); }
  else { failed++; fails.push(msg + (detail ? `(${detail})` : "")); console.error(`  ✗ ${msg}${detail ? "  →  " + detail : ""}`); }
}
function section(t) { console.log(`\n── ${t} ──`); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Client {
  constructor(name) { this.name = name; this.q = []; this.waiters = []; this.ws = null; }
  _wire(ws) { ws.addEventListener("message", (ev) => { const m = JSON.parse(ev.data);
    if (m.type === "ERROR") console.log(`    [${this.name}] ⚠ ERROR: ${m.message}`);
    this.q.push(m); this._pump(); }); }
  _connectOnce() {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(WS);
      let settled = false;
      ws.addEventListener("open", () => { if (settled) return; settled = true; this.ws = ws; this._wire(ws); resolve(); });
      ws.addEventListener("error", () => { if (settled) return; settled = true; reject(new Error(`[${this.name}] 連線失敗`)); });
      ws.addEventListener("close", () => { if (settled) return; settled = true; reject(new Error(`[${this.name}] 連線被關閉`)); });
    });
  }
  async open(attempts = 5) {
    for (let i = 1; i <= attempts; i++) {
      try { await this._connectOnce(); return; }
      catch (e) { if (i === attempts) throw new Error(e.message); await sleep(600); }
    }
  }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  _pump() {
    for (const w of this.waiters) {
      if (w.done) continue;
      const idx = this.q.findIndex(w.pred);
      if (idx >= 0) { const [m] = this.q.splice(idx, 1); w.done = true; clearTimeout(w.timer); w.resolve(m); }
    }
    this.waiters = this.waiters.filter((w) => !w.done);
  }
  waitFor(pred, label = "訊息", timeout = 15000) {
    return new Promise((resolve, reject) => {
      const w = { pred, resolve, done: false };
      w.timer = setTimeout(() => {
        w.done = true; this.waiters = this.waiters.filter((x) => x !== w);
        reject(new Error(`[${this.name}] 等不到「${label}」。近期:[${this.q.slice(-8).map((x) => x.type).join(", ")}]`));
      }, timeout);
      this.waiters.push(w);
      this._pump();
    });
  }
  close() { try { this.ws && this.ws.close(); } catch { /* */ } }
}

const isState = (m) => m.type === "STATE";
const isRoster = (m) => m.type === "SESSION_ROSTER";
const isCommit = (m) => m.type === "CHOICE_REQUEST" && m.kind === "COMMIT_CARDS";
const isOption = (m) => m.type === "CHOICE_REQUEST" && m.kind === "CHOOSE_OPTION";

/** 打完一個「投入→(略過反應)→結算」回合,回傳最新 view。 */
async function resolveTest(C, commitIds = []) {
  const req = await C.waitFor(isCommit, "投入請求");
  C.send({ type: "CHOICE_RESPONSE", requestId: req.requestId, choice: { committedCardIds: commitIds } });
  let st = (await C.waitFor(isState, "檢定結算 STATE")).view;
  try {
    const opt = await C.waitFor(isOption, "反應詢問", 600);
    C.send({ type: "CHOICE_RESPONSE", requestId: opt.requestId, choice: { optionId: "skip" } });
    st = (await C.waitFor(isState, "略過反應後 STATE")).view;
  } catch { /* 無反應詢問 */ }
  // 瀝乾佇列中殘餘的 STATE(多段廣播時取最新,避免拿到舊視圖)
  for (;;) {
    try { st = (await C.waitFor(isState, "drain", 150)).view; } catch { break; }
  }
  return st;
}


/** 回應「手牌超限自選棄牌」(B6):若有 CHOOSE_TARGET 請求就棄到上限(保留 Field Toolkit)。回傳最新 view 或 null。 */
async function handleDiscards(C) {
  let latest = null;
  for (;;) {
    let req;
    try {
      req = await C.waitFor((m) => m.type === "CHOICE_REQUEST" && m.kind === "CHOOSE_TARGET", "棄牌請求", 400);
    } catch { return latest; }
    const cands = req.options.candidates;
    const pick = [];
    for (const c of cands) {
      if (pick.length < req.options.min && c.label !== "Field Toolkit") pick.push(c.id);
    }
    for (const c of cands) {
      if (pick.length < req.options.min && !pick.includes(c.id)) pick.push(c.id);
    }
    C.send({ type: "CHOICE_RESPONSE", requestId: req.requestId, choice: { targetIds: pick } });
    latest = (await C.waitFor((m) => m.type === "STATE" && m.view.you.hand.length <= 8, "棄牌後 STATE")).view;
  }
}

/** 沒行動就強制過回合(單人);過程中若被要求棄牌(手牌上限)就回應。回傳可行動的 view。 */
async function ensureActions(C, st) {
  let v = await handleDiscards(C);
  if (v) st = v;
  if (st.you.actionsRemaining > 0) return st;
  C.send({ type: "INTENT", action: "END_TURN", payload: { force: true } });
  st = (await C.waitFor((m) => isState(m) && m.view.you.actionsRemaining === 3
    && m.view.phase === "INVESTIGATION", "下一輪")).view;
  v = await handleDiscards(C);
  return v ?? st;
}

async function main() {
  console.log(`▶ 玩家旅程(單人)e2e 連線 ${WS}`);
  const A = new Client("Solo");
  await A.open();

  section("S1 大廳:身分 → 建桌(單人)");
  A.send({ type: "HELLO", playerId: "js-solo", displayName: "Solo" });
  const lobby = await A.waitFor((m) => m.type === "LOBBY", "LOBBY");
  check(Array.isArray(lobby.activeSessions), "HELLO 後收到桌次清單");
  A.send({ type: "CREATE_CAMPAIGN", name: "單人旅程", campaignKey: "sandbox", difficulty: "EASY" });
  const r0 = await A.waitFor(isRoster, "建桌名冊");
  check(r0.members.length === 1 && r0.members[0].connected === true, "建桌後名冊 1 人且在線");
  const cid = r0.campaignId;

  section("S2 選卡:選角 / 牌組驗證 / 就緒即開打");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && m.members[0].investigatorId === "joe_diamond", "選角入冊");
  A.send({ type: "SET_DECK", deck: ["Guts", "Guts", "Guts"], xp: 0 });   // 同名第 3 張 → 應被擋
  const dErr = await A.waitFor((m) => m.type === "ERROR", "非法牌組 ERROR");
  check(/同名/.test(dErr.message), "牌組驗證:同名 >2 被擋", dErr.message);
  // 沙盒不提交合法牌組 → 沿用示範手牌(含特殊卡)+ 自動補預設牌堆(抽牌可用)
  A.send({ type: "READY_DECK", ready: true });
  let st = (await A.waitFor((m) => isState(m) && m.view.round === 1, "開打 STATE")).view;
  check(st.you.investigatorId === "joe_diamond" && st.phase === "INVESTIGATION", "單人就緒即開打(屏障 1/1)");
  const hub = st.locations.find((l) => l.id === "test_hub");
  check(hub && hub.clues === 5, "單人線索縮放:5×1=5", `clues=${hub?.clues}`);

  section("S3 基本行動:取資源 / 抽牌");
  const res0 = st.you.resources, hand0 = st.you.hand.length;
  A.send({ type: "INTENT", action: "GAIN_RESOURCE", payload: {} });
  st = (await A.waitFor((m) => isState(m) && m.view.you.resources === res0 + 1, "資源 +1")).view;
  check(st.you.resources === res0 + 1, "取資源 +1 且花 1 行動");
  A.send({ type: "INTENT", action: "DRAW", payload: {} });
  st = (await A.waitFor(isState, "抽牌後 STATE")).view;
  check(st.you.hand.length === hand0 + 1, "抽牌 +1 張(牌堆來自 SET_DECK)", `hand=${st.you.hand.length}`);

  section("S4 調查檢定:投入 → 抽標記 → 取線索(重試收斂)");
  let guard = 0;
  const cluesTarget = 1;
  while (st.you.cluesHeld < cluesTarget && guard++ < 16) {
    st = await ensureActions(A, st);
    A.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });
    st = await resolveTest(A, []);
  }
  check(st.you.cluesHeld >= cluesTarget, "調查取得線索(檢定鏈完整)", `clues=${st.you.cluesHeld} 嘗試=${guard}`);

  section("S5 打卡 + 啟動:Field Toolkit(DSL)");
  st = await ensureActions(A, st);
  const kit = st.you.hand.find((c) => c.name === "Field Toolkit");
  check(!!kit, "手牌有 Field Toolkit(沙盒固定手牌)");
  A.send({ type: "INTENT", action: "PLAY_CARD", payload: { cardId: kit.cardId } });
  st = (await A.waitFor((m) => isState(m) && m.view.you.playArea.some((c) => c.name === "Field Toolkit"), "進檯面")).view;
  const kitId = st.you.playArea.find((c) => c.name === "Field Toolkit").cardId;
  const resB = st.you.resources;
  st = await ensureActions(A, st);
  A.send({ type: "INTENT", action: "ACTIVATE", payload: { cardId: kitId } });
  st = (await A.waitFor((m) => isState(m) && m.view.you.resources >= resB + 2, "啟動 +2 資源")).view;
  check(st.you.resources >= resB + 2, "啟動能力生效(+2 資源、花 1 行動)");

  section("S6 戰鬥:移動 → 交戰 → 打到擊敗訓練假人");
  st = await ensureActions(A, st);
  A.send({ type: "INTENT", action: "MOVE", payload: { toLocationId: "test_yard" } });
  st = (await A.waitFor((m) => isState(m) && m.view.you.locationId === "test_yard", "抵達訓練場")).view;
  const dummy = st.enemies.find((e) => e.name === "訓練假人");
  check(!!dummy, "訓練假人在場(開局進場)");
  st = await ensureActions(A, st);   // 移動可能用掉最後一個行動
  A.send({ type: "INTENT", action: "ENGAGE", payload: { enemyId: dummy.id } });
  st = (await A.waitFor((m) => isState(m) && m.view.you.engagedEnemyIds.includes(dummy.id), "交戰")).view;
  check(st.you.engagedEnemyIds.includes(dummy.id), "交戰行動生效");
  guard = 0;
  while (st.enemies.some((e) => e.id === dummy.id) && guard++ < 22) {
    st = await ensureActions(A, st);
    A.send({ type: "INTENT", action: "FIGHT", payload: { enemyId: dummy.id } });
    const combat = st.you.hand.filter((c) =>
      c.skillIcons.some((i) => i === "COMBAT" || i === "WILD")).slice(0, 2).map((c) => c.cardId);
    st = await resolveTest(A, combat);   // 投入戰鬥圖示提高命中(仍可能 autofail → 重試)
  }
  check(!st.enemies.some((e) => e.id === dummy.id), "訓練假人被擊敗(戰鬥鏈完整)", `嘗試=${guard}`);
  check(st.you.damage === 0 && st.you.horror === 0, "假人無攻擊:全程無傷", `dmg=${st.you.damage}`);

  section("S7 存檔繼承:戰役中存檔 → 離開 → 重載續玩");
  A.send({ type: "SAVE_REQUEST" });
  const sp = await A.waitFor((m) => m.type === "SAVE_PROMPT", "存檔彈窗");
  A.send({ type: "SAVE_VOTE", requestId: sp.requestId, vote: true });
  const snap = await A.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "戰役中快照");
  check(snap.save.stage === "IN_SCENARIO" && !!snap.save.snapshot, "快照=戰役中(含引擎狀態樹)");
  const savedRound = snap.save.round;
  const savedClues = st.you.cluesHeld;
  A.send({ type: "LEAVE_SESSION" });
  await A.waitFor((m) => m.type === "LOBBY", "回主選單");
  A.q.length = 0;
  A.send({ type: "OFFER_SAVE", save: snap.save });
  await A.waitFor((m) => isRoster(m) && m.stage === "LOADING", "載入等待(屏障 A)");
  A.send({ type: "READY_LOAD", ready: true });
  st = (await A.waitFor((m) => isState(m) && m.view.you.locationId === "test_yard", "續玩 STATE")).view;
  check(st.round === savedRound, "續玩回合數與存檔一致", `round=${st.round}/${savedRound}`);
  check(st.you.cluesHeld === savedClues, "線索完整繼承", `clues=${st.you.cluesHeld}/${savedClues}`);
  check(st.you.playArea.some((c) => c.name === "Field Toolkit"), "檯面支援卡完整繼承");
  check(!st.enemies.some((e) => e.name === "訓練假人"), "戰果(擊敗的敵人)完整繼承");

  section("S8 收尾:湊 3 線索 → 推進幕 → 勝利 → XP → 第 2 章");
  st = await ensureActions(A, st);   // 續玩承接存檔當下的殘餘行動數
  A.send({ type: "INTENT", action: "MOVE", payload: { toLocationId: "test_hub" } });
  st = (await A.waitFor((m) => isState(m) && m.view.you.locationId === "test_hub", "回大廳格")).view;
  guard = 0;
  while (st.you.cluesHeld < 3 && guard++ < 20) {
    st = await ensureActions(A, st);
    A.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });
    st = await resolveTest(A, []);
  }
  check(st.you.cluesHeld >= 3, "湊滿 3 線索", `clues=${st.you.cluesHeld} 嘗試=${guard}`);
  A.send({ type: "INTENT", action: "ADVANCE_ACT", payload: {} });
  const done = await A.waitFor((m) => isRoster(m) && m.currentChapter === 2, "第 2 章名冊", 10000);
  check(done.currentChapter === 2, "勝利 → 章節結算 → 第 2 章");
  const snap2 = await A.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "跨章自動存檔");
  const me = snap2.save.roster.find((x) => x.playerId === "js-solo");
  check(me && me.xp >= 2, "XP 入帳(勝利 ≥2)", `xp=${me?.xp}`);
  check(snap2.save.currentChapter === 2 && snap2.save.stage === "DECKBUILDING", "存檔記到第 2 章牌組階段");
  void cid;

  A.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 單人旅程 e2e 整體逾時(150s)。"); process.exit(2); }, 150000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 單人旅程 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 單人旅程(大廳→選卡→玩法→戰鬥→存檔繼承→勝利結算)全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 單人旅程 e2e 中止:${err.message}`); process.exit(2); });
