// ════════════════════════════════════════════════════════════════════
//  Arkham 測試沙盒 e2e(docs/11 垂直切片):
//    建 campaignKey=sandbox 的桌 → 開打 → 打出特殊卡看效果:
//      緊急補給(+3資源)、放大鏡(智力+1、進檯面)、靈光一閃(免檢定取線索)
//
//  用法:  node e2e/sandbox-e2e.mjs [ws://host:8080]
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
const cardId = (view, name) => (view.you.hand.find((c) => c.name === name) || {}).cardId;
const inPlay = (view, name) => view.you.playArea.some((c) => c.name === name);

async function main() {
  console.log(`▶ 測試沙盒 e2e 連線 ${WS}`);
  const A = new Client("Tester");
  await A.open();
  A.send({ type: "HELLO", playerId: "sb-a", displayName: "Tester" });
  await A.waitFor((m) => m.type === "LOBBY", "LOBBY");
  A.send({ type: "CREATE_CAMPAIGN", name: "沙盒", campaignKey: "sandbox", difficulty: "STANDARD" });
  await A.waitFor(isRoster, "roster");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && m.members[0].investigatorId === "joe_diamond", "選 joe");
  A.send({ type: "READY_DECK", ready: true });   // 1 人 → 屏障放行 → 開打
  const s0 = await A.waitFor((m) => m.type === "STATE", "沙盒開打 STATE");

  section("① 沙盒起始狀態");
  check(s0.view.you.investigatorId === "joe_diamond", "控 joe_diamond");
  check(s0.view.you.resources === 15, "起始資源 15(沙盒高資源)", `res=${s0.view.you.resources}`);
  check(s0.view.you.locationId === "test_hub", "起始於 測試大廳(test_hub)", s0.view.you.locationId);
  const hub = s0.view.locations.find((l) => l.id === "test_hub");
  check(!!hub && hub.clues > 0, "測試大廳線索充足", `clues=${hub && hub.clues}`);
  const ec = s0.view.you.hand.find((c) => c.name === "Emergency Cache");
  check(!!ec && ec.cardType === "event" && ec.cost === 0, "手牌含 緊急補給(event, cost 0)");
  const mg = s0.view.you.hand.find((c) => c.name === "Magnifying Glass");
  check(!!mg && mg.cardType === "asset" && mg.cost === 1, "手牌含 放大鏡(asset, cost 1)");
  check(s0.view.you.playArea.length === 0, "檯面一開始是空的");

  section("② 打出 緊急補給 → +3 資源");
  A.send({ type: "INTENT", action: "PLAY_CARD", payload: { cardId: cardId(s0.view, "Emergency Cache") } });
  const s1 = await A.waitFor((m) => m.type === "STATE" && m.view.you.resources === 18, "緊急補給後 STATE");
  check(s1.view.you.resources === 18, "資源 15 → 18(+3)", `res=${s1.view.you.resources}`);
  check(s1.view.you.actionsRemaining === 2, "打卡花 1 行動(3→2)");

  section("③ 打出 放大鏡 → 智力 +1、進檯面");
  const baseInt = s1.view.you.skills.intellect;   // joe 基礎 4
  A.send({ type: "INTENT", action: "PLAY_CARD", payload: { cardId: cardId(s1.view, "Magnifying Glass") } });
  const s2 = await A.waitFor((m) => m.type === "STATE" && inPlay(m.view, "Magnifying Glass"), "放大鏡進檯面 STATE");
  check(s2.view.you.skills.intellect === baseInt + 1, "智力 +1(持續加值反映在視圖)", `int ${baseInt}→${s2.view.you.skills.intellect}`);
  check(inPlay(s2.view, "Magnifying Glass"), "放大鏡已在檯面(支援)");
  check(s2.view.you.resources === 17, "資源 18 → 17(費用 1)", `res=${s2.view.you.resources}`);

  section("④ 打出 靈光一閃 → 免檢定取 1 線索");
  const cluesBefore = s2.view.you.cluesHeld;
  A.send({ type: "INTENT", action: "PLAY_CARD", payload: { cardId: cardId(s2.view, "Working a Hunch") } });
  const s3 = await A.waitFor((m) => m.type === "STATE" && m.view.you.cluesHeld > cluesBefore, "取線索後 STATE");
  check(s3.view.you.cluesHeld === cluesBefore + 1, "線索 +1(免檢定)", `clues ${cluesBefore}→${s3.view.you.cluesHeld}`);
  check(s3.view.you.resources === 15, "資源 17 → 15(費用 2)", `res=${s3.view.you.resources}`);

  section("⑤ 工具箱:打出 → 啟動(C2)→ +2 資源、每輪一次");
  A.send({ type: "INTENT", action: "END_TURN", payload: { force: true } });   // 行動用完 → 先過回合
  const s4 = await A.waitFor((m) => m.type === "STATE" && m.view.round === 2 && m.view.you.actionsRemaining === 3, "第 2 輪 STATE");
  A.send({ type: "INTENT", action: "PLAY_CARD", payload: { cardId: cardId(s4.view, "Field Toolkit") } });
  const s5 = await A.waitFor((m) => m.type === "STATE" && inPlay(m.view, "Field Toolkit"), "工具箱進檯面 STATE");
  const kitId = s5.view.you.playArea.find((c) => c.name === "Field Toolkit").cardId;
  A.send({ type: "INTENT", action: "ACTIVATE", payload: { cardId: kitId } });
  const s6 = await A.waitFor((m) => m.type === "STATE" && m.view.you.resources === s5.view.you.resources + 2, "啟動後 STATE");
  check(s6.view.you.resources === s5.view.you.resources + 2, "啟動工具箱 → +2 資源", `res=${s6.view.you.resources}`);
  check(s6.view.you.actionsRemaining === 1, "打出+啟動共花 2 行動(3→1)");
  A.send({ type: "INTENT", action: "ACTIVATE", payload: { cardId: kitId } });
  const actErr = await A.waitFor((m) => m.type === "ERROR", "重複啟動 ERROR");
  check(/已啟動過/.test(actErr.message), "每輪限一次(同輪再啟動被擋)", actErr.message);

  A.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 沙盒 e2e 整體逾時(60s)。"); process.exit(2); }, 60000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 測試沙盒 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 測試沙盒(PLAY_CARD 特殊卡效果)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 沙盒 e2e 中止:${err.message}`); process.exit(2); });
