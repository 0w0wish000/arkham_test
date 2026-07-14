// ════════════════════════════════════════════════════════════════════
//  Arkham 玩法批次 e2e:
//    ① 難度→混沌袋:EASY 15 顆 vs EXPERT 17 顆
//    ② 牌組進遊戲(C-lite):SET_DECK 卡名 → 開局抽 5、牌堆數正確、起手 ⊆ 牌組
//    ③ END_TURN 屏障:一人結束不推進 → 全員完成才推進;整備抽 1 張
//    ④ force:一人強制全體結束 → 直接下一輪
//
//  用法:  node e2e/gameplay-e2e.mjs [ws://host:8080]
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

/** 一人開桌(選定難度)→ 選 joe → ready → 回傳開打 STATE。 */
async function soloStart(c, idp, name, difficulty) {
  c.send({ type: "HELLO", playerId: idp, displayName: name });
  await c.waitFor((m) => m.type === "LOBBY", "LOBBY");
  c.send({ type: "CREATE_CAMPAIGN", name, campaignKey: "core", difficulty });
  await c.waitFor(isRoster, "roster");
  c.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await c.waitFor((m) => isRoster(m) && m.members[0].investigatorId === "joe_diamond", "選角");
  c.send({ type: "READY_DECK", ready: true });
  return (await c.waitFor((m) => m.type === "STATE", "開打 STATE")).view;
}

async function main() {
  console.log(`▶ 玩法批次 e2e 連線 ${WS}`);

  // ══════════ ① 難度 → 混沌袋 ══════════
  section("① 難度 → 混沌袋組成");
  {
    const A = new Client("Easy");
    await A.open();
    const v = await soloStart(A, "gp-easy", "簡單團", "EASY");
    check(v.chaosBagSummary.total === 15, "EASY 袋 15 顆", `total=${v.chaosBagSummary.total}`);
    A.close();
    await sleep(200);
    const B = new Client("Expert");
    await B.open();
    const v2 = await soloStart(B, "gp-exp", "專家團", "EXPERT");
    check(v2.chaosBagSummary.total === 17, "EXPERT 袋 17 顆(負值更多更深)", `total=${v2.chaosBagSummary.total}`);
    B.close();
  }
  await sleep(300);

  // ══════════ ② 牌組進遊戲(C-lite)══════════
  section("② SET_DECK 牌組 → 洗牌 → 開局抽 5");
  {
    const A = new Client("Decker");
    await A.open();
    A.send({ type: "HELLO", playerId: "gp-deck", displayName: "Decker" });
    await A.waitFor((m) => m.type === "LOBBY", "LOBBY");
    A.send({ type: "CREATE_CAMPAIGN", name: "牌組團", campaignKey: "core", difficulty: "STANDARD" });
    await A.waitFor(isRoster, "roster");
    A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
    await A.waitFor((m) => isRoster(m) && m.members[0].investigatorId === "joe_diamond", "選角");
    const DECK = [
      "Deduction", "Deduction", "Perception", "Perception", "Overpower", "Overpower",
      "Magnifying Glass", "Machete", "Emergency Cache", "Emergency Cache",
      "Working a Hunch", "Unexpected Courage", "Unexpected Courage", "Vicious Blow", "Guts",
    ];
    A.send({ type: "SET_DECK", deck: DECK, xp: 0 });
    await sleep(150);
    A.send({ type: "READY_DECK", ready: true });
    const v = (await A.waitFor((m) => m.type === "STATE", "開打 STATE")).view;

    check(v.you.hand.length === 5, "開局起手 5 張", `hand=${v.you.hand.length}`);
    check(v.you.deckCount === 10, "牌堆剩 10(15 − 起手 5)", `deck=${v.you.deckCount}`);
    const names = new Set(DECK);
    check(v.you.hand.every((c) => names.has(c.name)), "起手全部來自你建的牌組",
      v.you.hand.map((c) => c.name).join("/"));
    const hasMeta = v.you.hand.every((c) => typeof c.cost === "number" && !!c.cardType);
    check(hasMeta, "起手卡帶型別/費用(目錄實體化)");
    A.close();
  }
  await sleep(300);

  // ══════════ ③ END_TURN 屏障 + 整備抽牌 ══════════
  section("③ END_TURN 屏障(2 人)+ 整備抽 1");
  {
    const A = new Client("Alice");
    const B = new Client("Bob");
    await Promise.all([A.open(), B.open()]);
    A.send({ type: "HELLO", playerId: "gp-a", displayName: "Alice" });
    B.send({ type: "HELLO", playerId: "gp-b", displayName: "Bob" });
    await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
    await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
    A.send({ type: "CREATE_CAMPAIGN", name: "屏障團", campaignKey: "core", difficulty: "STANDARD" });
    const r = await A.waitFor(isRoster, "roster");
    B.send({ type: "JOIN_SESSION", campaignId: r.campaignId });
    await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
    A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
    await A.waitFor((m) => isRoster(m) && memberOf(m, "Alice").investigatorId === "joe_diamond", "A 選角");
    B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
    await B.waitFor((m) => isRoster(m) && memberOf(m, "Bob").investigatorId === "daniela", "B 選角");
    A.send({ type: "READY_DECK", ready: true });
    B.send({ type: "READY_DECK", ready: true });
    const a0 = (await A.waitFor((m) => m.type === "STATE", "A 開打")).view;
    await B.waitFor((m) => m.type === "STATE", "B 開打");
    check(a0.you.hand.length === 5 && a0.you.deckCount === 10, "預設牌組也走管線(起手5/堆10)",
      `hand=${a0.you.hand.length} deck=${a0.you.deckCount}`);

    // A 結束 → 屏障擋住(仍第 1 輪);B 看得到 A 已完成
    A.send({ type: "INTENT", action: "END_TURN", payload: {} });
    const aDone = await A.waitFor((m) => m.type === "STATE" && m.view.you.turnDone === true, "A 已結束");
    check(aDone.view.round === 1, "A 一人結束 → 仍第 1 輪(屏障)", `round=${aDone.view.round}`);
    const bSee = await B.waitFor((m) => m.type === "STATE"
      && m.view.otherInvestigators.some((o) => o.investigatorId === "joe_diamond" && o.turnDone), "B 看到 A 完成");
    check(!!bSee, "隊友視圖看得到「A 已完成」");

    // B 結束 → 全員完成 → 敵人/整備/神話 → 第 2 輪;整備抽 1(手 5→6)、行動重置、turnDone 清空
    B.send({ type: "INTENT", action: "END_TURN", payload: {} });
    const a2 = await A.waitFor((m) => m.type === "STATE" && m.view.round === 2, "第 2 輪 STATE");
    check(a2.view.round === 2, "全員完成 → 推進第 2 輪");
    check(a2.view.you.hand.length === 6, "整備抽 1 張(5→6)", `hand=${a2.view.you.hand.length}`);
    check(a2.view.you.deckCount === 9, "牌堆 10→9", `deck=${a2.view.you.deckCount}`);
    check(a2.view.you.actionsRemaining === 3 && a2.view.you.turnDone === false, "行動重置 3、完成旗標清空");

    // ④ force:A 直接強制全體結束 → 第 3 輪(B 沒按也推進)
    section("④ force 強制全體結束");
    A.send({ type: "INTENT", action: "END_TURN", payload: { force: true } });
    const a3 = await A.waitFor((m) => m.type === "STATE" && m.view.round === 3, "第 3 輪 STATE");
    check(a3.view.round === 3, "force → 不等隊友直接下一輪", `round=${a3.view.round}`);
    A.close(); B.close();
  }
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 玩法 e2e 整體逾時(90s)。"); process.exit(2); }, 90000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 玩法批次 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 玩法批次(難度袋 / 牌組管線 / END_TURN 屏障)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 玩法 e2e 中止:${err.message}`); process.exit(2); });
