// ════════════════════════════════════════════════════════════════════
//  Arkham 創傷跨章 e2e(docs/09 §9;官方 p20-22 / docs/11 D4):
//    核心劇本:Joe 移入宿舍生成交戰的 Servant → 反覆抽牌吃趁隙攻擊直到被擊敗
//    → Daniela 撤退(全員退場,本章失利)→ 結算:Joe 依淘汰原因 +1 創傷、
//    Daniela(撤退)無創傷 → 名冊/存檔帶創傷 → 第 2 章開打:Joe 開局帶等量傷害/恐懼
//
//  用法:  node e2e/trauma-e2e.mjs [ws://host:8080]
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

async function main() {
  console.log(`▶ 創傷跨章 e2e 連線 ${WS}`);
  const A = new Client("Alice");
  const B = new Client("Bob");
  await Promise.all([A.open(), B.open()]);
  A.send({ type: "HELLO", playerId: "tr-a", displayName: "Alice" });
  B.send({ type: "HELLO", playerId: "tr-b", displayName: "Bob" });
  await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
  await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");

  section("① 開局(核心劇本)→ Joe 引怪硬吃趁隙攻擊");
  A.send({ type: "CREATE_CAMPAIGN", name: "創傷團", campaignKey: "core", difficulty: "STANDARD" });
  const r0 = await A.waitFor(isRoster, "A roster");
  B.send({ type: "JOIN_SESSION", campaignId: r0.campaignId });
  await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
  A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
  await A.waitFor((m) => isRoster(m) && seat(m, "tr-a")?.investigatorId === "joe_diamond", "A 選 joe");
  B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
  await B.waitFor((m) => isRoster(m) && seat(m, "tr-b")?.investigatorId === "daniela", "B 選 daniela");
  A.send({ type: "READY_DECK", ready: true });
  B.send({ type: "READY_DECK", ready: true });
  let st = await A.waitFor((m) => isState(m) && m.view.round === 1, "開打 STATE");
  check(st.view.you.elimination === null, "Joe 開局在場");

  // Joe 移入宿舍 → 揭示生成交戰的 Servant(1傷1懼);之後每個抽牌行動都吃趁隙攻擊
  A.send({ type: "INTENT", action: "MOVE", payload: { toLocationId: "dormitories" } });
  st = await A.waitFor((m) => isState(m) && m.view.you.locationId === "dormitories", "Joe 進宿舍");
  check(st.view.you.engagedEnemyIds.length === 1, "Servant 生成並交戰", JSON.stringify(st.view.you.engagedEnemyIds));

  section("② 連續硬吃直到被擊敗(個別淘汰)");
  let eliminated = null;
  for (let round = 1; round <= 6 && !eliminated; round++) {
    while (!eliminated && st.view.you.actionsRemaining > 0) {   // 照剩餘行動數抽(R1 移動花掉 1)
      A.send({ type: "INTENT", action: "DRAW", payload: {} });
      st = await A.waitFor(isState, `R${round} 抽牌後 STATE`);
      if (st.view.you.elimination) eliminated = st.view.you.elimination;
    }
    if (eliminated) break;
    A.send({ type: "INTENT", action: "END_TURN", payload: {} });
    B.send({ type: "INTENT", action: "END_TURN", payload: {} });
    // 等「下一輪開始(行動重置)或已淘汰」的 STATE —— 跳過屏障中間態
    st = await A.waitFor(
      (m) => isState(m) && (m.view.you.elimination
        || (m.view.phase === "INVESTIGATION" && m.view.you.actionsRemaining === 3)),
      `R${round} 回合結算後 STATE`);
    if (st.view.you.elimination) eliminated = st.view.you.elimination;
  }
  check(eliminated === "DAMAGE" || eliminated === "HORROR", "Joe 被擊敗退場(傷害或恐懼)", String(eliminated));
  const expectPhysical = eliminated === "DAMAGE" ? 1 : 0;
  const expectMental = eliminated === "HORROR" ? 1 : 0;

  section("③ Daniela 撤退 → 全員退場 → 章節結算指派創傷");
  B.send({ type: "INTENT", action: "RESIGN", payload: {} });
  const r1 = await A.waitFor(
    (m) => isRoster(m) && (seat(m, "tr-a")?.physicalTrauma > 0 || seat(m, "tr-a")?.mentalTrauma > 0),
    "結算後名冊(A 帶創傷)");
  const sa = seat(r1, "tr-a"), sb = seat(r1, "tr-b");
  check(sa.physicalTrauma === expectPhysical && sa.mentalTrauma === expectMental,
    `Joe 依淘汰原因得創傷(🩸${expectPhysical}/🧠${expectMental})`, JSON.stringify(sa));
  check(sb.physicalTrauma === 0 && sb.mentalTrauma === 0, "Daniela 撤退無創傷", JSON.stringify(sb));
  check(r1.currentChapter === 2, "推進到第 2 章", String(r1.currentChapter));
  const snap = await A.waitFor((m) => m.type === "CAMPAIGN_SNAPSHOT", "跨章自動存檔");
  const sm = snap.save.roster.find((x) => x.playerId === "tr-a");
  check(sm && sm.physicalTrauma === expectPhysical && sm.mentalTrauma === expectMental,
    "存檔記錄創傷(跨章保留)", JSON.stringify(sm));

  section("④ 第 2 章開打:創傷 = 開局傷害/恐懼");
  A.q.length = 0;   // 清掉第 1 章殘留 STATE(waitFor 會撿佇列裡的舊件)
  B.q.length = 0;
  A.send({ type: "READY_DECK", ready: true });
  B.send({ type: "READY_DECK", ready: true });
  const st2 = await A.waitFor(
    (m) => isState(m) && m.view.round === 1 && m.view.phase === "INVESTIGATION" && m.view.you.elimination === null,
    "第 2 章 STATE");
  check(st2.view.you.damage === expectPhysical && st2.view.you.horror === expectMental,
    `Joe 開局帶創傷(傷${expectPhysical}/懼${expectMental})`,
    `damage=${st2.view.you.damage} horror=${st2.view.you.horror}`);
  const stB = await B.waitFor((m) => isState(m) && m.view.you.elimination === null, "B 第 2 章 STATE");
  check(stB.view.you.damage === 0 && stB.view.you.horror === 0, "Daniela 無創傷,乾淨開局");

  A.close(); B.close();
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 創傷跨章 e2e 整體逾時(90s)。"); process.exit(2); }, 90000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 創傷跨章 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 創傷跨章(結算指派/存檔保留/下章開局套用)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 創傷跨章 e2e 中止:${err.message}`); process.exit(2); });
