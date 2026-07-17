// ════════════════════════════════════════════════════════════════════
//  Arkham 席位認領 e2e(docs/09 P6):換裝置(新 playerId)認回名冊席位
//    建桌選角存檔 → 全員離桌 → B 用存檔重開 → 名冊出現 A 的離線席位
//    → A2(新身分)入桌 → 認領在線席位被擋 → 認領 A 席位 → B 表決同意
//    → A2 繼承角色/牌組/XP、舊席位移除 → 自動出新存檔版本
//
//  用法:  node e2e/seat-claim-e2e.mjs [ws://host:8080]
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
const seat = (r, pid) => r.members.find((m) => m.playerId === pid);
const DECK = ["Deduction", "Perception", "Overpower", "Emergency Cache", "Working a Hunch"];

async function main() {
  console.log(`▶ 席位認領 e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);
  A.send({ type: "HELLO", playerId: "sc-a", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "sc-b", displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");

  section("① 建桌 → 選角/牌組 → 戰役存檔");
  A.send({ type: "CREATE_CAMPAIGN", name: "認領團", campaignKey: "core", difficulty: "STANDARD" });
  const r0 = await A.waitFor(isRoster, "A roster");
  const cid = r0.campaignId;
  B.send({ type: "JOIN_SESSION", campaignId: cid });
  await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && seat(m, "sc-a")?.investigatorId === "joe_diamond", "A 選 joe");
  A.send({ type: "SET_DECK", deck: DECK, xp: 3 });
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  await B.waitFor((m) => isRoster(m) && seat(m, "sc-b")?.investigatorId === "daniela", "B 選 daniela");
  A.send({ type: "SAVE_REQUEST" });
  const spA = await A.waitFor((m) => m.type === "SAVE_PROMPT", "A 存檔提示");
  const spB = await B.waitFor((m) => m.type === "SAVE_PROMPT", "B 存檔提示");
  A.send({ type: "SAVE_VOTE", requestId: spA.requestId, vote: true });
  B.send({ type: "SAVE_VOTE", requestId: spB.requestId, vote: true });
  const snapB = await B.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "B 收到存檔");
  check(snapB.save.roster.length === 2 && snapB.save.roster.some((s) => s.playerId === "sc-a"),
    "存檔含 A/B 兩席位", JSON.stringify(snapB.save.roster.map((s) => s.playerId)));

  section("② 全員離桌 → B 用存檔重開 → A 席位離線");
  A.send({ type: "LEAVE_SESSION" });
  B.send({ type: "LEAVE_SESSION" });
  await A.waitFor((m) => m.type === "LOBBY", "A 回主選單");
  await B.waitFor((m) => m.type === "LOBBY", "B 回主選單");
  B.q.length = 0;   // 清掉離桌前殘留的舊名冊訊息(waitFor 會從佇列撿舊件)
  B.send({ type: "OFFER_SAVE", save: snapB.save });
  const r1 = await B.waitFor(
    (m) => isRoster(m) && m.members.length === 2 && seat(m, "sc-a")?.connected === false,
    "B 重開桌(A 席位離線)");
  check(r1.members.length === 2, "還原名冊 2 席位");
  check(seat(r1, "sc-a")?.connected === false, "A 席位=離線(connected=false)",
    JSON.stringify(seat(r1, "sc-a")));
  check(seat(r1, "sc-b")?.connected === true, "B 席位=在線");

  section("③ A2(新裝置/新身分)入桌 → 認領");
  const A2 = new Client("Alice新機");
  await A2.open();
  A2.send({ type: "HELLO", playerId: "sc-a2", displayName: "Alice新機" });
  await A2.waitFor((m) => m.type === "LOBBY", "A2 LOBBY");
  A2.send({ type: "JOIN_SESSION", campaignId: cid });
  const r2 = await A2.waitFor((m) => isRoster(m) && m.members.length === 3, "A2 入桌(3 席位)");
  check(!seat(r2, "sc-a2")?.investigatorId, "A2 尚無角色(可認領)");

  // 守門:不能認領在線席位
  A2.send({ type: "CLAIM_SEAT", targetPlayerId: "sc-b" });
  const gErr = await A2.waitFor((m) => m.type === "ERROR", "認領在線席位 ERROR");
  check(/在線|無法認領/.test(gErr.message), "認領在線席位被擋", gErr.message);

  // 認領 A 的離線席位 → B 表決
  A2.send({ type: "CLAIM_SEAT", targetPlayerId: "sc-a" });
  const vp = await B.waitFor((m) => m.type === "VOTE_PROMPT", "B 收到認領表決");
  check(vp.subject === "sc-a" && /認領/.test(vp.reason), "表決內容=認領 A 席位", vp.reason);
  await sleep(300);
  check(!A2.q.some((m) => m.type === "VOTE_PROMPT"), "認領者本人不參與表決(未收提示)");
  B.send({ type: "VOTE", requestId: vp.requestId, yes: true });

  section("④ 通過:A2 繼承席位;自動出新存檔");
  const r3 = await A2.waitFor(
    (m) => isRoster(m) && m.members.length === 2 && seat(m, "sc-a2")?.investigatorId === "joe_diamond",
    "認領後名冊");
  check(!seat(r3, "sc-a"), "舊席位 sc-a 已移除");
  check(seat(r3, "sc-a2")?.investigatorId === "joe_diamond", "A2 繼承 joe_diamond");
  const snap2 = await A2.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "認領後自動存檔");
  const sm = snap2.save.roster.find((s) => s.playerId === "sc-a2");
  check(!!sm && sm.investigatorId === "joe_diamond" && sm.xp === 3 && (sm.deck || []).length === DECK.length,
    "新存檔:席位易主且牌組/XP 完整繼承", JSON.stringify(sm));
  check(!snap2.save.roster.some((s) => s.playerId === "sc-a"), "新存檔不再含舊 playerId");

  A.close(); B.close(); A2.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 席位認領 e2e 整體逾時(60s)。"); process.exit(2); }, 60000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 席位認領 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 席位認領(換裝置回歸)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 席位認領 e2e 中止:${err.message}`); process.exit(2); });
