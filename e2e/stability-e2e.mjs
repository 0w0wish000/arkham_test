// ════════════════════════════════════════════════════════════════════
//  Arkham 穩定性 e2e —— 三個「過去會永久卡死」的情境:
//    ① 存檔投票中有人掉線 → 投票應自動結案(不懸置)
//    ② 換角投票中有人掉線 → 投票應自動結案
//    ③ 技能檢定屏障中掉線且不歸隊 → 寬限逾時自動視為不投入(屏障解開)
//       (需伺服器以 ARKHAM_TAKEOVER_GRACE_MS 縮短寬限;run.mjs 已設 3000)
//
//  用法:  node e2e/stability-e2e.mjs [ws://host:8080]
// ════════════════════════════════════════════════════════════════════

const URL = process.argv[2] || process.env.ARKHAM_WS || "ws://localhost:8080";
const WS = `${URL.replace(/\/$/, "")}/ws/game`;
const GRACE_MS = Number(process.env.ARKHAM_TAKEOVER_GRACE_MS || 3000);

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

/** 建一張 2 人桌(A 建、B 加入,各選角),回傳 campaignId。 */
async function setupTable(A, B, idPrefix, tableName) {
  A.send({ type: "HELLO", playerId: `${idPrefix}-a`, displayName: "Alice" });
  B.send({ type: "HELLO", playerId: `${idPrefix}-b`, displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: tableName, campaignKey: "core", difficulty: "STANDARD" });
  const r = await A.waitFor(isRoster, "A roster");
  B.send({ type: "JOIN_SESSION", campaignId: r.campaignId });
  await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && memberOf(m, "Alice").investigatorId === "joe_diamond", "A 選 joe");
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  await B.waitFor((m) => isRoster(m) && memberOf(m, "Bob").investigatorId === "daniela", "B 選 daniela");
  return r.campaignId;
}

async function main() {
  console.log(`▶ 穩定性 e2e 連線 ${WS}(寬限 ${GRACE_MS}ms)`);

  // ══════════ ① 存檔投票中掉線 → 自動結案 ══════════
  section("① 存檔投票:B 未投票就掉線 → 不懸置,存檔完成");
  {
    const A = new Client("A1"); const B = new Client("B1");
    await Promise.all([A.open(), B.open()]);
    await setupTable(A, B, "st1", "投票掉線團");
    A.send({ type: "SAVE_REQUEST" });
    const pa = await A.waitFor((m) => m.type === "SAVE_PROMPT", "A SAVE_PROMPT");
    await B.waitFor((m) => m.type === "SAVE_PROMPT", "B SAVE_PROMPT");
    A.send({ type: "SAVE_VOTE", requestId: pa.requestId, vote: true });   // A 同意
    await sleep(200);
    B.close();                                                             // B 沒投就掉線
    const snap = await A.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "A 收到存檔(投票自動結案)");
    check(!!snap.save, "B 掉線後投票結案 → 存檔完成(過去會永久懸置)");
    A.close();
  }
  await sleep(300);

  // ══════════ ② 換角投票中掉線 → 自動結案 ══════════
  section("② 換角投票:B 未投票就掉線 → 以現票結案");
  {
    const A = new Client("A2"); const B = new Client("B2");
    await Promise.all([A.open(), B.open()]);
    await setupTable(A, B, "st2", "換角掉線團");
    B.send({ type: "PROPOSE_NEW_CHARACTER", playerId: "st2-a" });
    const pa = await A.waitFor((m) => m.type === "VOTE_PROMPT", "A VOTE_PROMPT");
    await B.waitFor((m) => m.type === "VOTE_PROMPT", "B VOTE_PROMPT");
    A.send({ type: "VOTE", requestId: pa.requestId, yes: true });          // A 同意
    await sleep(200);
    B.close();                                                             // B 沒投就掉線
    const after = await A.waitFor(
      (m) => isRoster(m) && (m.deadInvestigators || []).includes("joe_diamond"),
      "投票結案(joe 封鎖)");
    check((after.deadInvestigators || []).includes("joe_diamond"),
      "B 掉線後換角投票以現票結案(1 票同意過半)→ joe 封鎖");
    check(memberOf(after, "Alice").investigatorId === null, "Alice 可改選新角色");
    A.close();
  }
  await sleep(300);

  // ══════════ ③ 檢定屏障掉線不歸隊 → 寬限逾時自動不投入 ══════════
  section("③ 檢定屏障:B 掉線不歸隊 → 寬限逾時自動視為不投入");
  {
    const A = new Client("A3"); const B = new Client("B3");
    await Promise.all([A.open(), B.open()]);
    await setupTable(A, B, "st3", "屏障逾時團");
    A.send({ type: "READY_DECK", ready: true });
    B.send({ type: "READY_DECK", ready: true });
    await A.waitFor((m) => m.type === "STATE", "A 開打");
    await B.waitFor((m) => m.type === "STATE", "B 開打");

    A.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });        // 開屏障(A+B 都被要求投入)
    const ra = await A.waitFor((m) => m.type === "CHOICE_REQUEST", "A 投入請求");
    await B.waitFor((m) => m.type === "CHOICE_REQUEST", "B 投入請求");
    A.send({ type: "CHOICE_RESPONSE", requestId: ra.requestId, choice: { committedCardIds: [] } });   // A 回了
    await sleep(200);
    B.close();                                                             // B 卡著屏障掉線、不回來

    // 寬限逾時 → 自動「視為不投入」→ 屏障解開 → 檢定結算 → A 收到 STATE(行動 3→2)
    const skipEvt = await A.waitFor((m) => m.type === "EVENT" && /視為不投入/.test(m.message),
      "自動略過事件", GRACE_MS + 9000);
    check(!!skipEvt, "寬限逾時 → 廣播「視為不投入」", skipEvt && skipEvt.message);
    const st = await A.waitFor((m) => m.type === "STATE" && m.view.you.actionsRemaining === 2,
      "檢定結算後 STATE", 8000);
    check(st.view.you.actionsRemaining === 2, "屏障解開、檢定完成(行動 3→2;過去會永久卡住)");
    A.close();
  }
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 穩定性 e2e 整體逾時(90s)。"); process.exit(2); }, 90000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 穩定性 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 穩定性(投票掉線回收 / 屏障逾時逃生)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 穩定性 e2e 中止:${err.message}`); process.exit(2); });
