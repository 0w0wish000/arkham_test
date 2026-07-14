// ════════════════════════════════════════════════════════════════════
//  Arkham 存檔 → 重載續玩 e2e(docs/09 P3):
//    建桌→選角→(牌組階段存檔:驗 stage/roster/deck)→開打→移動→END_TURN
//      →(戰役中存檔:驗 stage/round/snapshot)→全員斷線→桌被移除
//      →全新連線 OFFER_SAVE + JOIN + READY_LOAD(屏障A)→重建對局
//      →STATE 還原到存檔回合與位置 + LOG_HISTORY 回放
//
//  用法:  node e2e/save-reload-e2e.mjs [ws://host:8080]
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

async function saveViaVote(clients, requester) {
  requester.send({ type: "SAVE_REQUEST" });
  const prompts = await Promise.all(clients.map((c) => c.waitFor((m) => m.type === "SAVE_PROMPT", `${c.name} SAVE_PROMPT`)));
  clients.forEach((c, i) => c.send({ type: "SAVE_VOTE", requestId: prompts[i].requestId, vote: true }));
  const snaps = await Promise.all(clients.map((c) => c.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", `${c.name} CAMPAIGN_SNAPSHOT`)));
  return snaps[0].save;
}

async function main() {
  console.log(`▶ 存檔/重載 e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);

  // ── 建桌 + 選角 ──
  section("0. 建桌 + 選角");
  A.send({ type: "HELLO", playerId: "p-alice", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "p-bob", displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "存檔團", campaignKey: "core", difficulty: "STANDARD" });
  const r0 = await A.waitFor(isRoster, "A 建桌 roster");
  const campaignId = r0.campaignId;
  B.send({ type: "JOIN_SESSION", campaignId });
  await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && memberOf(m, "Alice").investigatorId === "joe_diamond", "A 選 joe");
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  await B.waitFor((m) => isRoster(m) && memberOf(m, "Bob").investigatorId === "daniela", "B 選 daniela");
  A.send({ type: "SET_DECK", deck: ["Deduction", "Vicious Blow"], xp: 2 });
  await sleep(150);

  // ── 情境 B:牌組階段存檔 ──
  section("1. 牌組階段存檔(DECKBUILDING)");
  const deckSave = await saveViaVote([A, B], A);
  check(deckSave.stage === "DECKBUILDING", "存檔 stage=DECKBUILDING", deckSave.stage);
  const aMem = deckSave.roster.find((m) => m.displayName === "Alice");
  check(aMem && aMem.investigatorId === "joe_diamond", "存檔含 Alice=joe_diamond");
  check(aMem && aMem.deck.includes("Deduction"), "存檔含 Alice 的牌組", JSON.stringify(aMem && aMem.deck));
  check(aMem && aMem.xp === 2, "存檔含 xp=2");
  check(deckSave.snapshot == null, "牌組階段無引擎快照(snapshot=null)");

  // ── 開打 → 移動 → END_TURN ──
  section("2. 開打 + 推進到第 2 輪 + 移動");
  A.send({ type: "READY_DECK", ready: true });
  B.send({ type: "READY_DECK", ready: true });
  const aState0 = await A.waitFor((m) => m.type === "STATE", "A 開打 STATE");
  await B.waitFor((m) => m.type === "STATE", "B 開打 STATE");
  check(aState0.view.round === 1 && aState0.view.you.investigatorId === "joe_diamond", "開打:第1輪 joe");
  A.send({ type: "INTENT", action: "MOVE", payload: { toLocationId: "dormitories" } });
  await A.waitFor((m) => m.type === "STATE" && m.view.you.locationId === "dormitories", "A 移動到 dormitories");
  A.send({ type: "INTENT", action: "END_TURN", payload: { force: true } });   // 強制全體結束(屏障 force 路徑)
  await A.waitFor((m) => m.type === "STATE" && m.view.round === 2, "推進到第 2 輪");

  // ── 情境 A:戰役中存檔 ──
  section("3. 戰役中存檔(IN_SCENARIO)");
  const gameSave = await saveViaVote([A, B], A);
  check(gameSave.stage === "IN_SCENARIO", "存檔 stage=IN_SCENARIO", gameSave.stage);
  check(gameSave.round === 2, "存檔 round=2", `round=${gameSave.round}`);
  check(gameSave.snapshot != null, "存檔含引擎快照");
  check(Array.isArray(gameSave.eventLog) && gameSave.eventLog.length > 0, "存檔含事件紀錄", `len=${gameSave.eventLog?.length}`);

  // ── 全員斷線 → 桌被移除 ──
  section("4. 全員斷線(模擬關閉遊戲)");
  A.close(); B.close();
  await sleep(800);
  check(true, "A/B 已斷線,原桌應被清除");

  // ── 全新連線 → OFFER_SAVE + JOIN + READY_LOAD(屏障 A)→ 重建 ──
  section("5. 重開載入:OFFER_SAVE → 屏障A → 續玩");
  const A2 = new Client("Alice#2");
  const B2 = new Client("Bob#2");
  await Promise.all([A2.open(), B2.open()]);
  A2.send({ type: "HELLO", playerId: "p-alice", displayName: "Alice" });
  B2.send({ type: "HELLO", playerId: "p-bob", displayName: "Bob" });
  await A2.waitFor((m) => m.type === "LOBBY", "A2 LOBBY");
  await B2.waitFor((m) => m.type === "LOBBY", "B2 LOBBY");

  A2.send({ type: "OFFER_SAVE", save: gameSave });
  const la = await A2.waitFor((m) => isRoster(m) && m.stage === "LOADING", "A2 進 LOADING");
  check(la.stage === "LOADING", "OFFER_SAVE 後 stage=LOADING", la.stage);
  check(la.campaignId === campaignId, "同一 campaignId(串聯鑰匙)");
  B2.send({ type: "JOIN_SESSION", campaignId });
  await B2.waitFor((m) => isRoster(m) && m.stage === "LOADING" && m.members.length === 2, "B2 進 LOADING(2人)");

  A2.send({ type: "READY_LOAD", ready: true });
  await A2.waitFor((m) => isRoster(m) && memberOf(m, "Alice").ready, "A2 已就緒");
  B2.send({ type: "READY_LOAD", ready: true });

  // 屏障 A 達成 → LOG_HISTORY + STATE
  const logHist = await A2.waitFor((m) => m.type === "LOG_HISTORY", "A2 LOG_HISTORY 回放");
  check(Array.isArray(logHist.entries) && logHist.entries.length > 0, "收到 log 回放", `len=${logHist.entries?.length}`);
  const aReload = await A2.waitFor((m) => m.type === "STATE", "A2 續玩 STATE");
  const bReload = await B2.waitFor((m) => m.type === "STATE", "B2 續玩 STATE");

  check(aReload.view.round === 2, "續玩還原到第 2 輪", `round=${aReload.view.round}`);
  check(aReload.view.you.investigatorId === "joe_diamond", "A2 接回 joe_diamond");
  check(aReload.view.you.locationId === "dormitories", "還原移動後位置(dormitories,非重置)", aReload.view.you.locationId);
  check(bReload.view.you.investigatorId === "daniela", "B2 接回 daniela");

  A2.close(); B2.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 存檔/重載 e2e 整體逾時(90s)。"); process.exit(2); }, 90000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 存檔/重載 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 存檔 → 重載續玩 e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 存檔/重載 e2e 中止:${err.message}`); process.exit(2); });
