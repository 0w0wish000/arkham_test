// ════════════════════════════════════════════════════════════════════
//  Arkham 死亡換角投票 + XP 上限 e2e(docs/09 §10–11):
//    XP 超上限被擋 → 對 A 發起換角投票 → 過半通過 → joe 永久封鎖、A 可改選
//      → A 想重選 joe 被擋 → A 改選 dexter 成功
//
//  用法:  node e2e/char-vote-e2e.mjs [ws://host:8080]
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
const memberOf = (r, name) => r.members.find((m) => m.displayName === name);

async function main() {
  console.log(`▶ 死亡換角 / XP 上限 e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);
  A.send({ type: "HELLO", playerId: "cv-a", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "cv-b", displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "換角團", campaignKey: "core", difficulty: "STANDARD" });
  const r = await A.waitFor(isRoster, "A roster");
  const cid = r.campaignId;
  B.send({ type: "JOIN_SESSION", campaignId: cid });
  await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && memberOf(m, "Alice").investigatorId === "joe_diamond", "A 選 joe");
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  await B.waitFor((m) => isRoster(m) && memberOf(m, "Bob").investigatorId === "daniela", "B 選 daniela");

  // ── XP 上限 ──
  section("① XP 上限驗證");
  A.send({ type: "SET_DECK", deck: [], xp: 999 });
  const xpErr = await A.waitFor((m) => m.type === "ERROR", "超額 XP ERROR");
  check(/上限/.test(xpErr.message), "XP 超上限被擋", xpErr.message);
  A.send({ type: "SET_DECK", deck: ["Deduction"], xp: 5 });   // 合法,不應報錯
  await sleep(200);
  check(!A.q.some((m) => m.type === "ERROR"), "合法 XP(5)不報錯");

  // ── 死亡換角投票 ──
  section("② 死亡換角投票 → 通過 → 永久封鎖");
  B.send({ type: "PROPOSE_NEW_CHARACTER", playerId: "cv-a" });   // 對 A 的角色發起
  const pa = await A.waitFor((m) => m.type === "VOTE_PROMPT", "A 收到換角投票");
  const pb = await B.waitFor((m) => m.type === "VOTE_PROMPT", "B 收到換角投票");
  check(pa.subject === "cv-a" && /陣亡/.test(pa.reason), "投票對象=Alice,理由含陣亡", pa.reason);
  A.send({ type: "VOTE", requestId: pa.requestId, yes: true });
  B.send({ type: "VOTE", requestId: pb.requestId, yes: true });

  // 等「投票後」的名冊(A 無角色 且 joe 已封鎖)—— 明確避開 A 選角前的舊名冊
  const after = await A.waitFor(
    (m) => isRoster(m) && memberOf(m, "Alice").investigatorId === null && (m.deadInvestigators || []).includes("joe_diamond"),
    "投票通過後的名冊");
  check(memberOf(after, "Alice").investigatorId === null, "通過後 Alice 需重新選角(investigatorId=null)");
  check((after.deadInvestigators || []).includes("joe_diamond"), "joe_diamond 已列入永久封鎖", JSON.stringify(after.deadInvestigators));

  // ── 封鎖:不能再選 joe;可改選別的 ──
  section("③ 封鎖角色不可重用 / 改選新角色");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  const lockErr = await A.waitFor((m) => m.type === "ERROR", "重選封鎖角色 ERROR");
  check(/陣亡|無法再度使用/.test(lockErr.message), "重選 joe 被擋(已陣亡封鎖)", lockErr.message);
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "dexter_drake" });
  const repick = await A.waitFor((m) => isRoster(m) && memberOf(m, "Alice").investigatorId === "dexter_drake", "A 改選 dexter");
  check(memberOf(repick, "Alice").investigatorId === "dexter_drake", "Alice 成功改帶新角色 dexter_drake");

  A.close(); B.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 死亡換角 e2e 整體逾時(60s)。"); process.exit(2); }, 60000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 死亡換角 / XP 上限 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 死亡換角投票 + deadInvestigators 封鎖 + XP 上限 e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 死亡換角 e2e 中止:${err.message}`); process.exit(2); });
