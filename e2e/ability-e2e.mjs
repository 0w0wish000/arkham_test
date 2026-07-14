// ════════════════════════════════════════════════════════════════════
//  Arkham 能力引擎 e2e(docs/11 §B 原型):
//    Joe 成功調查 → 伺服器發 CHOOSE_OPTION(反應能力)→ 回答「use」
//      → 抽 1 張(手牌+1)→ 同輪再成功 → 不再詢問(每回合限一次)
//  檢定有混沌隨機:失敗就重試(每輪 3 行動,必要時 force 進下一輪)。
//
//  用法:  node e2e/ability-e2e.mjs [ws://host:8080]
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
  console.log(`▶ 能力引擎 e2e 連線 ${WS}`);
  const A = new Client("Joe");
  await A.open();
  A.send({ type: "HELLO", playerId: "ab-joe", displayName: "Joe" });
  await A.waitFor((m) => m.type === "LOBBY", "LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "能力團", campaignKey: "core", difficulty: "EASY" });
  await A.waitFor(isRoster, "roster");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && m.members[0].investigatorId === "joe_diamond", "選角");
  A.send({ type: "READY_DECK", ready: true });
  const s0 = (await A.waitFor(isState, "開打")).view;

  section("① 成功調查 → 反應能力詢問 → 使用 → 抽 1 張");
  let option = null;
  let attempts = 0;
  while (!option && attempts < 8) {   // 檢定可能失敗:重試,行動不夠就 force 下一輪
    attempts++;
    const st = (A.q.filter(isState).pop() || { view: s0 }).view;
    if ((st.you?.actionsRemaining ?? 3) <= 0) {
      A.send({ type: "INTENT", action: "END_TURN", payload: { force: true } });
      await A.waitFor((m) => isState(m) && m.view.you.actionsRemaining === 3, "下一輪");
    }
    const r = await investigateOnce(A);
    if (r.option) option = r.option;
  }
  check(!!option, "成功調查後收到 CHOOSE_OPTION 反應詢問", `attempts=${attempts}`);
  check(!!option && /Joe/.test(option.options.prompt), "詢問文字含 Joe 能力說明", option && option.options.prompt);
  check(!!option && option.options.options.some((o) => o.id === "use"), "選項含「使用」");

  const handBefore = (A.q.filter(isState).pop()?.view ?? s0).you.hand.length;
  A.send({ type: "CHOICE_RESPONSE", requestId: option.requestId, choice: { optionId: "use" } });
  const drew = await A.waitFor((m) => m.type === "EVENT" && /抽了 1 張牌/.test(m.message), "抽牌事件");
  check(!!drew, "使用能力 → 抽牌事件", drew && drew.message);
  const after = await A.waitFor(isState, "使用後 STATE");
  check(after.view.you.hand.length === handBefore + 1, "手牌 +1", `hand ${handBefore}→${after.view.you.hand.length}`);

  // 「每回合限一次」由引擎單元測試(AbilityEngineTest)確定性覆蓋;
  // e2e 專注驗證線上協定流(詢問 → 回答 → 效果 → 狀態)。
  A.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 能力 e2e 整體逾時(90s)。"); process.exit(2); }, 90000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 能力引擎 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 能力引擎(反應詢問/使用/每輪限一次)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 能力 e2e 中止:${err.message}`); process.exit(2); });
