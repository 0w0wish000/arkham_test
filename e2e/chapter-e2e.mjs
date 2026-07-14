// ════════════════════════════════════════════════════════════════════
//  Arkham 跨章推進 e2e(docs/11 D2/D5):
//    solo Joe 贏下第 1 章(湊 2 線索 → ADVANCE_ACT)→ 章節結算:+XP、
//    回牌組大廳、currentChapter=2、自動存檔(CAMPAIGN_SNAPSHOT 含章數)。
//  檢定有混沌隨機:失敗就重試。
//
//  用法:  node e2e/chapter-e2e.mjs [ws://host:8080]
// ════════════════════════════════════════════════════════════════════

const URL = process.argv[2] || process.env.ARKHAM_WS || "ws://localhost:8080";
const WS = `${URL.replace(/\/$/, "")}/ws/game`;

let passed = 0, failed = 0;
const fails = [];
function check(cond, msg, detail) {
  if (cond) { passed++; console.log(`  ✓ ${msg}`); }
  else { failed++; fails.push(msg); console.error(`  ✗ ${msg}${detail ? "  →  " + detail : ""}`); }
}
function section(t) { console.log(`\n── ${t} ──`); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Client {
  constructor(name) { this.name = name; this.q = []; this.waiters = []; this.ws = null; }
  _wire(ws) { ws.addEventListener("message", (ev) => { this.q.push(JSON.parse(ev.data)); this._pump(); }); }
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
  waitFor(pred, label = "訊息", timeout = 12000) {
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

const isRoster = (m) => m.type === "SESSION_ROSTER";
const isState = (m) => m.type === "STATE";
const memberOf = (r, name) => r.members.find((m) => m.displayName === name);
const isCommit = (m) => m.type === "CHOICE_REQUEST" && m.kind === "COMMIT_CARDS";
const isOption = (m) => m.type === "CHOICE_REQUEST" && m.kind === "CHOOSE_OPTION";

/** 做一次調查(回空投入),回傳 { option? }。伺服器先推 STATE、後發 CHOOSE_OPTION。 */
async function investigateOnce(A) {
  A.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });
  const req = await A.waitFor(isCommit, "投入請求");
  A.send({ type: "CHOICE_RESPONSE", requestId: req.requestId, choice: { committedCardIds: [] } });
  await A.waitFor(isState, "結算後 STATE");
  try {
    const option = await A.waitFor(isOption, "反應詢問", 700);   // 成功且本輪未用才會來
    return { option };
  } catch {
    return { option: null };   // 失敗、或已用過(每回合限一次)
  }
}

async function main() {
  console.log(`▶ 跨章 e2e 連線 ${WS}`);
  const A = new Client("Joe");
  await A.open();
  A.send({ type: "HELLO", playerId: "ch-joe", displayName: "Joe" });
  await A.waitFor((m) => m.type === "LOBBY", "LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "跨章團", campaignKey: "core_2026", difficulty: "EASY" });
  const r0 = await A.waitFor(isRoster, "roster");
  check(r0.currentChapter === 1, "開檔於第 1 章", `ch=${r0.currentChapter}`);
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && m.members[0].investigatorId === "joe_diamond", "選角");
  A.send({ type: "READY_DECK", ready: true });
  let st = (await A.waitFor(isState, "開打")).view;

  section("① 湊 2 線索 → 推進幕(贏下第 1 章)");
  let guard = 0;
  while (st.you.cluesHeld < 2 && guard++ < 14) {
    if (st.you.actionsRemaining <= 0) {
      A.send({ type: "INTENT", action: "END_TURN", payload: { force: true } });
      st = (await A.waitFor((m) => isState(m) && m.view.you.actionsRemaining === 3, "下一輪")).view;
      continue;
    }
    A.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });
    const req = await A.waitFor(isCommit, "投入請求");
    A.send({ type: "CHOICE_RESPONSE", requestId: req.requestId, choice: { committedCardIds: [] } });
    st = (await A.waitFor(isState, "結算 STATE")).view;
    try {
      const opt = await A.waitFor(isOption, "反應詢問", 600);   // 成功時 Joe 能力 → 跳過
      A.send({ type: "CHOICE_RESPONSE", requestId: opt.requestId, choice: { optionId: "skip" } });
      st = (await A.waitFor(isState, "略過後 STATE")).view;
    } catch { /* 失敗或已用過 → 無詢問 */ }
  }
  check(st.you.cluesHeld >= 2, "湊到 2 線索", `clues=${st.you.cluesHeld} guard=${guard}`);

  A.send({ type: "INTENT", action: "ADVANCE_ACT", payload: {} });
  const evt = await A.waitFor((m) => m.type === "EVENT" && /章節/.test(m.message), "章節結算事件", 8000);
  check(/經驗/.test(evt.message), "結算播報含經驗", evt.message);

  section("② 回牌組大廳:第 2 章 + 存檔含章數/XP");
  const r2 = await A.waitFor((m) => isRoster(m) && m.stage === "DECKBUILDING" && m.currentChapter === 2, "第 2 章名冊");
  check(r2.currentChapter === 2, "currentChapter=2");
  check(memberOf(r2, "Joe")?.ready === false, "ready 已重置");
  const snap = await A.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "跨章自動存檔");
  check(snap.save.currentChapter === 2, "存檔 currentChapter=2", `ch=${snap.save.currentChapter}`);
  const joe = snap.save.roster.find((x) => x.displayName === "Joe");
  check(joe && joe.xp >= 2, "Joe 獲得經驗(≥2:勝利+勝利地點)", `xp=${joe && joe.xp}`);
  check(snap.save.stage === "DECKBUILDING", "存檔 stage 回牌組大廳");
  A.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 能力 e2e 整體逾時(90s)。"); process.exit(2); }, 90000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 跨章 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 跨章推進(結算/回大廳/存檔)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 能力 e2e 中止:${err.message}`); process.exit(2); });
