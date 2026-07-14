// ════════════════════════════════════════════════════════════════════
//  Arkham 牌組大廳 → 開打 e2e(docs/09 P2-1):
//    HELLO → 建桌/加入 → 各自 PICK_INVESTIGATOR(含「已被選走」擋回)
//      → READY_DECK → 屏障 B 觸發 START_SCENARIO → 兩端收到初始 STATE
//  驗證:名冊反映選角/就緒、選角唯一、開打後正確調查員 + 線索×人數縮放。
//
//  用法:  node e2e/deckbuild-e2e.mjs [ws://host:8080]
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
      catch (e) { if (i === attempts) throw new Error(e.message + "(重試失敗)"); await new Promise((r) => setTimeout(r, 600)); }
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
  waitFor(pred, label = "訊息", timeout = 10000) {
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

const memberOf = (roster, name) => roster.members.find((m) => m.displayName === name);

async function main() {
  console.log(`▶ 牌組大廳 e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);

  // 建桌 + 加入(沿用大廳流程)
  section("0. 建桌 + 加入");
  A.send({ type: "HELLO", playerId: "p-alice", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "p-bob", displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "Alice LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "Bob LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "開打團", campaignKey: "core", difficulty: "STANDARD" });
  const r1 = await A.waitFor((m) => m.type === "SESSION_ROSTER", "Alice 建桌 roster");
  const campaignId = r1.campaignId;
  B.send({ type: "JOIN_SESSION", campaignId });
  await B.waitFor((m) => m.type === "SESSION_ROSTER" && m.members.length === 2, "Bob 加入 roster(2)");
  check(r1.stage === "DECKBUILDING", "初始 stage=DECKBUILDING", r1.stage);

  // ── 1. 選角(含唯一性)──
  section("1. PICK_INVESTIGATOR / 選角");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  const aPick = await A.waitFor((m) => m.type === "SESSION_ROSTER" && memberOf(m, "Alice").investigatorId === "joe_diamond", "Alice 選 joe_diamond");
  check(memberOf(aPick, "Alice").investigatorId === "joe_diamond", "Alice = joe_diamond");
  // Bob 想選同一個 → 應被擋
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  const dupErr = await B.waitFor((m) => m.type === "ERROR", "選角衝突 ERROR");
  check(/選走|taken|已被/.test(dupErr.message), "重複選角被擋(已被隊友選走)", dupErr.message);
  // Bob 改選 daniela
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  const bPick = await B.waitFor((m) => m.type === "SESSION_ROSTER" && memberOf(m, "Bob").investigatorId === "daniela", "Bob 選 daniela");
  check(memberOf(bPick, "Bob").investigatorId === "daniela", "Bob = daniela");

  // ── 2. 準備完成 → 屏障 B → 開打 ──
  section("2. READY_DECK / 屏障 → 開打");
  A.send({ type: "READY_DECK", ready: true });
  const aReady = await A.waitFor((m) => m.type === "SESSION_ROSTER" && memberOf(m, "Alice").ready, "Alice 就緒");
  check(memberOf(aReady, "Alice").ready === true, "Alice ready=true");
  check(!memberOf(aReady, "Bob").ready, "此時 Bob 尚未就緒 → 未開打");

  // Bob 就緒 → 全員 ready → START_SCENARIO → 兩端收 STATE
  B.send({ type: "READY_DECK", ready: true });
  const aState = await A.waitFor((m) => m.type === "STATE", "Alice 收到初始 STATE(開打)");
  const bState = await B.waitFor((m) => m.type === "STATE", "Bob 收到初始 STATE(開打)");

  check(aState.view.round === 1, "開打:第 1 輪", `round=${aState.view.round}`);
  check(aState.view.phase === "INVESTIGATION", "階段 INVESTIGATION", aState.view.phase);
  check(aState.view.you.investigatorId === "joe_diamond", "Alice 控 joe_diamond", aState.view.you.investigatorId);
  check(bState.view.you.investigatorId === "daniela", "Bob 控 daniela", bState.view.you.investigatorId);
  check(aState.view.you.actionsRemaining === 3, "起始 3 行動");
  const fr = aState.view.locations.find((l) => l.id === "friends_room");
  check(!!fr && fr.clues === 4, "friends_room 線索=4(clueValue2 × 2人:難度隨人數)", `clues=${fr && fr.clues}`);
  // 對手可見(過濾視圖):Alice 應看到 daniela 在同地點
  const other = aState.view.otherInvestigators.find((o) => o.investigatorId === "daniela");
  check(!!other, "Alice 視圖含隊友 daniela");

  A.close(); B.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 牌組 e2e 整體逾時(60s)。"); process.exit(2); }, 60000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 牌組大廳 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 牌組大廳 → 開打 e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 牌組 e2e 中止:${err.message}`); process.exit(2); });
