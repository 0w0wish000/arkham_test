// ════════════════════════════════════════════════════════════════════
//  Arkham 大廳交握 e2e(docs/09 P1)—— 用存檔/桌次取代 room:
//    HELLO(Alice/Bob) → LOBBY → Alice CREATE_CAMPAIGN → Bob 看到桌次
//      → Bob JOIN_SESSION → 兩端名冊 2 人 + 旁白事件 → Bob LEAVE → 名冊剩 1
//  只用 Node 內建 WebSocket(需 Node 22+),零相依。
//
//  用法:  node e2e/lobby-e2e.mjs [ws://host:8080]
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
      catch (e) { if (i === attempts) throw new Error(e.message + "(重試 " + attempts + " 次,伺服器沒起來?)"); await new Promise((r) => setTimeout(r, 600)); }
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

const names = (roster) => roster.members.map((m) => m.displayName).sort().join(",");

async function main() {
  console.log(`▶ 大廳 e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);

  // ── 1. HELLO → 初始 LOBBY ──
  section("1. HELLO / 進主選單");
  A.send({ type: "HELLO", playerId: "p-alice", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "p-bob", displayName: "Bob" });
  const aLobby0 = await A.waitFor((m) => m.type === "LOBBY", "Alice 初始 LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "Bob 初始 LOBBY");
  check(Array.isArray(aLobby0.activeSessions), "LOBBY 帶 activeSessions 陣列");

  // ── 2. Alice 開新檔案(建桌)──
  section("2. CREATE_CAMPAIGN / 建桌");
  A.send({ type: "CREATE_CAMPAIGN", name: "週五團", campaignKey: "core", difficulty: "STANDARD" });
  const aRoster1 = await A.waitFor((m) => m.type === "SESSION_ROSTER", "Alice 建桌後 SESSION_ROSTER");
  check(aRoster1.members.length === 1, "建桌後名冊 1 人", `${aRoster1.members.length}`);
  check(aRoster1.members[0].displayName === "Alice", "名冊含 Alice");
  check(aRoster1.name === "週五團", "桌名=週五團", aRoster1.name);
  check(aRoster1.campaignKey === "core" && aRoster1.difficulty === "STANDARD", "戰役=core 難度=STANDARD");
  check(aRoster1.members[0].investigatorId === null, "尚未選角(investigatorId=null)");
  const campaignId = aRoster1.campaignId;

  // Bob(在主選單)應收到含此桌的 LOBBY 更新
  const bLobby = await B.waitFor((m) => m.type === "LOBBY" && m.activeSessions.length >= 1, "Bob 看到新桌次");
  const seen = bLobby.activeSessions.find((s) => s.campaignId === campaignId);
  check(!!seen, "桌次清單含新桌", campaignId);
  check(!!seen && seen.members.some((x) => x.displayName === "Alice"), "桌次列出桌內成員 Alice");

  // ── 3. Bob 加入桌次 ──
  section("3. JOIN_SESSION / 加入");
  B.send({ type: "JOIN_SESSION", campaignId });
  const bRoster = await B.waitFor((m) => m.type === "SESSION_ROSTER" && m.members.length === 2, "Bob 加入後名冊(2人)");
  check(names(bRoster) === "Alice,Bob", "Bob 端名冊 = Alice+Bob", names(bRoster));
  const aRoster2 = await A.waitFor((m) => m.type === "SESSION_ROSTER" && m.members.length === 2, "Alice 端看到 2 人");
  check(names(aRoster2) === "Alice,Bob", "Alice 端名冊也同步 = Alice+Bob", names(aRoster2));
  const joinEvt = await A.waitFor((m) => m.type === "EVENT" && /Bob/.test(m.message) && /加入/.test(m.message), "加入旁白事件");
  check(!!joinEvt, "旁白:Bob 加入了調查", joinEvt && joinEvt.message);

  // ── 4. Bob 離桌 → 名冊剩 Alice,Bob 回主選單 ──
  section("4. LEAVE_SESSION / 離桌");
  B.send({ type: "LEAVE_SESSION" });
  const aRoster3 = await A.waitFor((m) => m.type === "SESSION_ROSTER" && m.members.length === 1, "Bob 離開後 Alice 名冊(1人)");
  check(names(aRoster3) === "Alice", "離桌後只剩 Alice", names(aRoster3));
  const leaveEvt = await A.waitFor((m) => m.type === "EVENT" && /Bob/.test(m.message) && /脫離/.test(m.message), "離桌旁白事件");
  check(!!leaveEvt, "旁白:Bob 脫離調查", leaveEvt && leaveEvt.message);
  const bBack = await B.waitFor((m) => m.type === "LOBBY", "Bob 回主選單 LOBBY");
  check(!!bBack, "Bob 回到主選單(收到 LOBBY)");

  A.close(); B.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 大廳 e2e 整體逾時(60s)。"); process.exit(2); }, 60000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 大廳 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 大廳交握 e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 大廳 e2e 中止:${err.message}`); process.exit(2); });
