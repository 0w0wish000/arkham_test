// ════════════════════════════════════════════════════════════════════
//  Arkham 戰役日誌 / 劇本指示 e2e(docs/09 §11.5 混合制;docs/11 D6/D7):
//    記事 → 加卡 → 調創傷 → 全隊同步 CAMPAIGN_LOG + 自動存檔
//    → 移除不存在的卡被擋 → 存檔重開後日誌保留(入桌同步)
//
//  用法:  node e2e/campaign-log-e2e.mjs [ws://host:8080]
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
const isLog = (m) => m.type === "CAMPAIGN_LOG";
const seat = (r, pid) => r.members.find((m) => m.playerId === pid);

async function main() {
  console.log(`▶ 戰役日誌 e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);
  A.send({ type: "HELLO", playerId: "cl-a", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "cl-b", displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "日誌團", campaignKey: "core", difficulty: "STANDARD" });
  const r0 = await A.waitFor(isRoster, "A roster");
  B.send({ type: "JOIN_SESSION", campaignId: r0.campaignId });
  await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  await B.waitFor((m) => isRoster(m) && seat(m, "cl-b")?.investigatorId === "daniela", "選角完成");
  B.send({ type: "SET_DECK", deck: ["Machete", "Guts"], xp: 0 });
  await sleep(200);

  section("① 記事(旗標)→ 全隊同步 + 自動存檔");
  A.send({ type: "APPLY_LOG", action: "RECORD", text: "旗標:牧師還活著" });
  const l1 = await B.waitFor(isLog, "B 收到日誌");
  check(l1.entries.length === 1 && /牧師還活著/.test(l1.entries[0].text), "記事入日誌", JSON.stringify(l1.entries));
  check(l1.entries[0].by === "Alice" && l1.entries[0].chapter === 1, "記錄套用者與章數");
  const s1 = await A.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "自動存檔");
  check((s1.save.campaignLog || []).length === 1, "存檔含戰役日誌");

  section("② 劇本給卡:加卡到 Bob 牌組");
  A.send({ type: "APPLY_LOG", action: "ADD_CARD", targetPlayerId: "cl-b", cardName: "Emergency Cache" });
  await B.waitFor((m) => isLog(m) && m.entries.length === 2, "日誌 2 則");
  const s2 = await B.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT" && (m.save.campaignLog || []).length === 2, "加卡後存檔(日誌 2 則)");
  const bm = s2.save.roster.find((x) => x.playerId === "cl-b");
  check(bm.deck.includes("Emergency Cache") && bm.deck.length === 3, "Bob 牌組多了劇本給的卡", JSON.stringify(bm.deck));

  section("③ 調創傷 + 守門");
  B.send({ type: "APPLY_LOG", action: "ADJUST_TRAUMA", targetPlayerId: "cl-a", physicalDelta: 1, mentalDelta: 0 });
  const r1 = await A.waitFor((m) => isRoster(m) && seat(m, "cl-a")?.physicalTrauma === 1, "創傷更新的名冊");
  check(seat(r1, "cl-a").physicalTrauma === 1, "劇本指示調創傷生效");
  A.send({ type: "APPLY_LOG", action: "REMOVE_CARD", targetPlayerId: "cl-b", cardName: "沒這張" });
  const gErr = await A.waitFor((m) => m.type === "ERROR", "移除不存在的卡 ERROR");
  check(/不在/.test(gErr.message), "移除不存在的卡被擋", gErr.message);
  A.send({ type: "APPLY_LOG", action: "RECORD", text: "" });
  const gErr2 = await A.waitFor((m) => m.type === "ERROR", "空記事 ERROR");
  check(/不可為空/.test(gErr2.message), "空記事被擋");

  section("④ 存檔重開 → 日誌保留");
  const s3 = await B.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT" && (m.save.campaignLog || []).length === 3, "最新存檔(日誌 3 則)");
  A.send({ type: "LEAVE_SESSION" });
  B.send({ type: "LEAVE_SESSION" });
  await A.waitFor((m) => m.type === "LOBBY", "A 回主選單");
  await B.waitFor((m) => m.type === "LOBBY", "B 回主選單");
  B.q.length = 0;
  B.send({ type: "OFFER_SAVE", save: s3.save });
  const l2 = await B.waitFor(isLog, "重開後入桌同步日誌");
  check(l2.entries.length === 3 && /牧師還活著/.test(l2.entries[0].text), "日誌跨存檔保留(3 則)", `len=${l2.entries.length}`);

  A.close(); B.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 戰役日誌 e2e 整體逾時(60s)。"); process.exit(2); }, 60000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 戰役日誌 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 戰役日誌 / 劇本指示套用(混合制)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 戰役日誌 e2e 中止:${err.message}`); process.exit(2); });
