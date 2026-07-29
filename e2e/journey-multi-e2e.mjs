// ════════════════════════════════════════════════════════════════════
//  Arkham 玩家旅程 e2e【多人(2 人)· 單場劇本(沙盒)】
//  模擬兩位玩家組隊從頭玩到底,對應測試案例 M1–M8:
//    M1 大廳/組隊(建桌→清單可見→加入)   M2 交互-選角衝突(搶角被擋)
//    M3 選卡+屏障(全員就緒才開打)+ 視圖過濾(看不到隊友手牌)
//    M4 交互-協力投入(隊友限投 1 張)      M5 自由順序(不等回合輪替)
//    M6 戰鬥分工(B 獨自打;A 不同地點不收投入請求)
//    M7 存檔繼承-多人(投票存檔→兩人本機都有→「非建桌者」重開→A 回座續玩)
//    M8 收尾(湊線索推進幕→勝利→兩人 XP 相同→第 2 章)
//
//  用法:  node e2e/journey-multi-e2e.mjs [ws://host:8080]
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
const seat = (r, pid) => r.members.find((m) => m.playerId === pid);

/** 瀝乾佇列殘餘 STATE,回傳最新 view(沒有就回傳原值)。 */
async function drainState(C, st) {
  for (;;) {
    try { st = (await C.waitFor(isState, "drain", 150)).view; } catch { break; }
  }
  return st;
}

async function skipOptionIfAny(C) {
  try {
    const opt = await C.waitFor(isOption, "反應詢問", 600);
    C.send({ type: "CHOICE_RESPONSE", requestId: opt.requestId, choice: { optionId: "skip" } });
    await C.waitFor(isState, "略過反應後 STATE");
  } catch { /* 無 */ }
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

async function ensureActionsMulti(A, B, st) {
  let v = await handleDiscards(A);
  if (v) st = v;
  await handleDiscards(B);   // 隊友欠棄也要回應(不擋 A,但 B 後續行動會被擋)
  if (st.you.actionsRemaining > 0) return st;
  A.send({ type: "INTENT", action: "END_TURN", payload: { force: true } });
  st = (await A.waitFor((m) => isState(m) && m.view.you.actionsRemaining === 3
    && m.view.phase === "INVESTIGATION", "下一輪")).view;
  v = await handleDiscards(A);
  if (v) st = v;
  await handleDiscards(B);
  return st;
}

async function main() {
  console.log(`▶ 玩家旅程(多人)e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);

  section("M1 大廳/組隊:建桌 → 清單可見 → 加入");
  A.send({ type: "HELLO", playerId: "jm-a", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "jm-b", displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "雙人旅程", campaignKey: "sandbox", difficulty: "EASY" });
  const r0 = await A.waitFor(isRoster, "A 建桌");
  const cid = r0.campaignId;
  const bl = await B.waitFor((m) => m.type === "LOBBY"
    && m.activeSessions.some((s2) => s2.campaignId === cid), "B 看到新桌");
  check(bl.activeSessions.some((s2) => s2.campaignId === cid && s2.name === "雙人旅程"),
    "組隊入口:B 的桌次清單出現 A 的桌");
  B.send({ type: "JOIN_SESSION", campaignId: cid });
  const r1 = await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 入桌");
  check(seat(r1, "jm-a")?.connected && seat(r1, "jm-b")?.connected, "名冊 2 人皆在線");

  section("M2 交互:搶同一位調查員被擋");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && seat(m, "jm-a")?.investigatorId === "joe_diamond", "A 選 joe");
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  const pickErr = await B.waitFor((m) => m.type === "ERROR", "搶角 ERROR");
  check(/選走/.test(pickErr.message), "同角互斥:B 搶 joe 被擋", pickErr.message);
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  await B.waitFor((m) => isRoster(m) && seat(m, "jm-b")?.investigatorId === "daniela", "B 改選 daniela");

  section("M3 選卡 + 屏障:全員就緒才開打;視圖過濾");
  // 沙盒不提交牌組 → 兩人都沿用示範手牌(含戰鬥技能卡)+ 預設牌堆
  A.send({ type: "READY_DECK", ready: true });
  const rHalf = await A.waitFor((m) => isRoster(m) && seat(m, "jm-a")?.ready === true, "A 就緒名冊");
  check(rHalf.stage === "DECKBUILDING", "只有 A 就緒 → 尚未開打(屏障擋住)");
  B.send({ type: "READY_DECK", ready: true });
  let stA = (await A.waitFor((m) => isState(m) && m.view.round === 1, "A 開打 STATE")).view;
  let stB = (await B.waitFor((m) => isState(m) && m.view.round === 1, "B 開打 STATE")).view;
  check(stA.you.investigatorId === "joe_diamond" && stB.you.investigatorId === "daniela",
    "全員就緒 → 開打,各自控自己的角色");
  const hub = stA.locations.find((l) => l.id === "test_hub");
  check(hub && hub.clues === 10, "雙人線索縮放:5×2=10", `clues=${hub?.clues}`);
  const mateA = stA.otherInvestigators.find((o) => o.investigatorId === "daniela");
  check(!!mateA && typeof mateA.handCount === "number" && !("hand" in (mateA ?? {})),
    "視圖過濾:看得到隊友手牌張數、看不到內容");

  section("M4 交互:協力投入(隊友限 1 張)");
  A.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });
  const reqA = await A.waitFor(isCommit, "A(檢定者)投入請求");
  const reqB = await B.waitFor(isCommit, "B(同地點隊友)投入請求");
  check(reqA.options.maxCommit >= 99, "檢定者投入不設限");
  check(reqB.options.maxCommit === 1, "隊友最多投 1 張(官方 p15)", `max=${reqB.options.maxCommit}`);
  const bCard = reqB.options.eligibleCards[0];
  B.send({ type: "CHOICE_RESPONSE", requestId: reqB.requestId,
    choice: { committedCardIds: bCard ? [bCard.cardId] : [] } });
  A.send({ type: "CHOICE_RESPONSE", requestId: reqA.requestId, choice: { committedCardIds: [] } });
  stA = (await A.waitFor(isState, "檢定結算(全員回覆才動)")).view;
  await skipOptionIfAny(A);
  check(true, "協力投入結算完成(A 空投、B 投 1)");

  section("M5 自由順序:B 不用等 A,立刻自己行動");
  const resB0 = stB.you.resources;
  B.send({ type: "INTENT", action: "GAIN_RESOURCE", payload: {} });
  stB = (await B.waitFor((m) => isState(m) && m.view.you.resources === resB0 + 1, "B 資源 +1")).view;
  check(stB.you.resources === resB0 + 1, "自由順序:誰先丟就是誰的(B 直接行動)");

  section("M6 戰鬥分工:B 移動去打假人;A 不同地點收不到投入請求");
  B.send({ type: "INTENT", action: "MOVE", payload: { toLocationId: "test_yard" } });
  stB = (await B.waitFor((m) => isState(m) && m.view.you.locationId === "test_yard", "B 抵訓練場")).view;
  const dummy = stB.enemies.find((e) => e.name === "訓練假人");
  check(!!dummy, "訓練假人在場");
  let guard = 0;
  let aGotCommit = false;
  while (stB.enemies.some((e) => e.id === dummy.id) && guard++ < 22) {
    if (stB.you.actionsRemaining <= 0) {
      B.send({ type: "INTENT", action: "END_TURN", payload: { force: true } });
      stB = (await B.waitFor((m) => isState(m) && m.view.you.actionsRemaining === 3
        && m.view.phase === "INVESTIGATION", "下一輪(B)")).view;
      const vb = await handleDiscards(B);
      if (vb) stB = vb;
      await handleDiscards(A);   // A 在別地也可能欠棄(整備全員抽牌)
      continue;
    }
    B.send({ type: "INTENT", action: "FIGHT", payload: { enemyId: dummy.id } });
    const req = await B.waitFor(isCommit, "B 戰鬥投入");
    try { await A.waitFor(isCommit, "A 不該收到", 400); aGotCommit = true; } catch { /* 正確:收不到 */ }
    const combat = stB.you.hand.filter((c) =>
      c.skillIcons.some((i) => i === "COMBAT" || i === "WILD")).slice(0, 2).map((c) => c.cardId);
    B.send({ type: "CHOICE_RESPONSE", requestId: req.requestId, choice: { committedCardIds: combat } });
    stB = (await B.waitFor(isState, "戰鬥結算")).view;
    await skipOptionIfAny(B);
    stB = await drainState(B, stB);   // 取最新視圖(擊敗結算可能多段廣播)
  }
  check(!stB.enemies.some((e) => e.id === dummy.id), "B 擊敗訓練假人", `嘗試=${guard}`);
  check(!aGotCommit, "不同地點的 A 從未收到投入請求(視圖/資格過濾)");

  section("M7 存檔繼承(多人):投票存檔 → 兩人都有 → 非建桌者重開 → A 回座");
  A.send({ type: "SAVE_REQUEST" });
  const spA = await A.waitFor((m) => m.type === "SAVE_PROMPT", "A 存檔彈窗");
  const spB = await B.waitFor((m) => m.type === "SAVE_PROMPT", "B 存檔彈窗");
  A.send({ type: "SAVE_VOTE", requestId: spA.requestId, vote: true });
  B.send({ type: "SAVE_VOTE", requestId: spB.requestId, vote: true });
  const snapA = await A.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "A 收到快照");
  const snapB = await B.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "B 收到快照");
  check(!!snapA && !!snapB && snapB.save.stage === "IN_SCENARIO", "存檔複製到兩人本機(戰役中)");
  const savedRound = snapB.save.round;
  A.send({ type: "LEAVE_SESSION" });
  B.send({ type: "LEAVE_SESSION" });
  await A.waitFor((m) => m.type === "LOBBY", "A 回主選單");
  await B.waitFor((m) => m.type === "LOBBY", "B 回主選單");
  A.q.length = 0; B.q.length = 0;
  B.send({ type: "OFFER_SAVE", save: snapB.save });     // 由「非建桌者」重開:存檔無單點
  await B.waitFor((m) => isRoster(m) && m.stage === "LOADING", "B 重開(載入等待)");
  A.send({ type: "JOIN_SESSION", campaignId: cid });     // A 回自己的座位
  await A.waitFor((m) => isRoster(m) && m.stage === "LOADING", "A 回座");
  A.send({ type: "READY_LOAD", ready: true });
  B.send({ type: "READY_LOAD", ready: true });
  stA = (await A.waitFor((m) => isState(m) && m.view.you.investigatorId === "joe_diamond", "A 續玩 STATE")).view;
  stB = (await B.waitFor((m) => isState(m) && m.view.you.investigatorId === "daniela", "B 續玩 STATE")).view;
  check(stA.round === savedRound && stB.round === savedRound, "兩人續玩回合一致", `round=${stA.round}`);
  check(stB.you.locationId === "test_yard", "B 位置完整繼承(訓練場)");
  check(!stB.enemies.some((e) => e.name === "訓練假人"), "戰果(假人已死)完整繼承");

  section("M8 收尾:湊 3 線索 → 推進幕 → 兩人 XP 相同 → 第 2 章");
  stA = (await (async () => {   // A 在 hub 調查;B 已在別地 —— B 需一起回覆投入嗎?不同地點不收
    let st = stA, g = 0;
    while (st.you.cluesHeld < 3 && g++ < 20) {
      st = await ensureActionsMulti(A, B, st);
      A.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });
      const req = await A.waitFor(isCommit, "A 投入");
      A.send({ type: "CHOICE_RESPONSE", requestId: req.requestId, choice: { committedCardIds: [] } });
      st = (await A.waitFor(isState, "結算")).view;
      await skipOptionIfAny(A);
      st = await drainState(A, st);
    }
    return st;
  })());
  check(stA.you.cluesHeld >= 3, "湊滿 3 線索(A 主力)", `clues=${stA.you.cluesHeld}`);
  A.send({ type: "INTENT", action: "ADVANCE_ACT", payload: {} });
  const done = await A.waitFor((m) => isRoster(m) && m.currentChapter === 2, "第 2 章名冊", 10000);
  check(done.currentChapter === 2, "勝利 → 第 2 章");
  const snap2 = await B.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT" && m.save.currentChapter === 2, "跨章存檔(B 也有)");
  const xa = snap2.save.roster.find((x) => x.playerId === "jm-a")?.xp ?? -1;
  const xb = snap2.save.roster.find((x) => x.playerId === "jm-b")?.xp ?? -2;
  check(xa === xb && xa >= 2, "兩人 XP 相同且入帳(各得等量)", `A=${xa} B=${xb}`);

  A.close(); B.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 多人旅程 e2e 整體逾時(150s)。"); process.exit(2); }, 150000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 多人旅程 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 多人旅程(組隊→屏障→協力→分工→存檔繼承→勝利結算)全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 多人旅程 e2e 中止:${err.message}`); process.exit(2); });
