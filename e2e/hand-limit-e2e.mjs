// ════════════════════════════════════════════════════════════════════
//  Arkham 手牌上限自選棄牌 e2e(docs/11 B6/E3):
//    Joe 抽到 8 張 → 整備 +1 = 9 超限 → 收 CHOOSE_TARGET(棄 1)
//    → 超限中行動被擋 → 張數不對被退回重問 → 正確棄 1 → 解鎖行動
//
//  用法:  node e2e/hand-limit-e2e.mjs [ws://host:8080]
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

const isRoster = (m) => m.type === "SESSION_ROSTER";
const isState = (m) => m.type === "STATE";
const seat = (r, pid) => r.members.find((m) => m.playerId === pid);
const isDiscardReq = (m) => m.type === "CHOICE_REQUEST" && m.kind === "CHOOSE_TARGET";

async function main() {
  console.log(`▶ 手牌上限 e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);
  A.send({ type: "HELLO", playerId: "hl-a", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "hl-b", displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "手牌團", campaignKey: "core", difficulty: "STANDARD" });
  const r0 = await A.waitFor(isRoster, "A roster");
  B.send({ type: "JOIN_SESSION", campaignId: r0.campaignId });
  await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && seat(m, "hl-a")?.investigatorId === "joe_diamond", "A 選 joe");
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  await B.waitFor((m) => isRoster(m) && seat(m, "hl-b")?.investigatorId === "daniela", "B 選 daniela");
  A.send({ type: "READY_DECK", ready: true });
  B.send({ type: "READY_DECK", ready: true });
  let st = await A.waitFor((m) => isState(m) && m.view.round === 1, "開打 STATE");
  check(st.view.you.hand.length === 5, "開局手牌 5 張");

  section("① 抽到 8 → 整備 +1 = 9 超限 → 收棄牌請求");
  for (let i = 0; i < 3; i++) {
    A.send({ type: "INTENT", action: "DRAW", payload: {} });
    st = await A.waitFor(isState, `抽牌 ${i + 1} 後 STATE`);
  }
  check(st.view.you.hand.length === 8, "抽滿 8 張", `hand=${st.view.you.hand.length}`);
  A.send({ type: "INTENT", action: "END_TURN", payload: {} });
  B.send({ type: "INTENT", action: "END_TURN", payload: {} });
  const req1 = await A.waitFor(isDiscardReq, "CHOOSE_TARGET 棄牌請求");
  check(req1.options.min === 1 && req1.options.max === 1, "需棄剛好 1 張", JSON.stringify({ min: req1.options.min, max: req1.options.max }));
  check(req1.options.candidates.length === 9, "候選 = 全部 9 張手牌", `${req1.options.candidates.length}`);

  section("② 超限中行動被擋;張數不對被退回重問");
  A.send({ type: "INTENT", action: "DRAW", payload: {} });
  const blocked = await A.waitFor((m) => m.type === "ERROR", "超限行動 ERROR");
  check(/先棄/.test(blocked.message), "超限者行動被擋", blocked.message);
  A.send({ type: "CHOICE_RESPONSE", requestId: req1.requestId, choice: { targetIds: [] } });
  const wrong = await A.waitFor((m) => m.type === "ERROR", "張數不對 ERROR");
  check(/剛好 1 張/.test(wrong.message), "棄 0 張被退回", wrong.message);
  const req2 = await A.waitFor(isDiscardReq, "重新發問");
  check(req2.requestId !== req1.requestId, "重新發問(新 requestId)");

  section("③ 正確棄 1 → 手牌 8、解鎖行動");
  const dropId = req2.options.candidates[2].id;   // 隨便挑第 3 張
  A.send({ type: "CHOICE_RESPONSE", requestId: req2.requestId, choice: { targetIds: [dropId] } });
  st = await A.waitFor((m) => isState(m) && m.view.round === 2 && m.view.you.hand.length === 8 && m.view.phase === "INVESTIGATION", "棄牌後 STATE(第 2 輪)");
  check(!st.view.you.hand.some((c) => c.cardId === dropId), "棄掉的是玩家選的那張");
  A.send({ type: "INTENT", action: "GAIN_RESOURCE", payload: {} });
  const st2 = await A.waitFor((m) => isState(m) && m.view.round === 2 && m.view.you.hand.length === 8 && m.view.you.actionsRemaining === 2, "解鎖後行動 STATE");
  check(st2.view.you.actionsRemaining === 2, "棄完即可正常行動");

  A.close(); B.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 手牌上限 e2e 整體逾時(60s)。"); process.exit(2); }, 60000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 手牌上限 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 手牌上限自選棄牌(CHOOSE_TARGET)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 手牌上限 e2e 中止:${err.message}`); process.exit(2); });
