// ════════════════════════════════════════════════════════════════════
//  Arkham 動態名冊 e2e(docs/09 P4):
//    ① 中離 + 難度隨人數:B 暫時脫離 → 只剩 A 開打 → 線索 = clueValue × 1
//    ② 接手:戰役中 A 掉線 → 全新連線 JOIN_SESSION → reattach 送回 STATE
//    ③ 重打掉線那一動:A 卡在投入屏障時掉線 → 重連補發同一 CHOICE_REQUEST
//
//  用法:  node e2e/dynamic-roster-e2e.mjs [ws://host:8080]
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
  console.log(`▶ 動態名冊 e2e 連線 ${WS}`);

  // ══════════ ① 中離 + 難度隨人數 ══════════
  section("① 中離 → 只剩 1 人開打 → 線索×人數縮放");
  {
    const A = new Client("Alice");
    const B = new Client("Bob");
    await Promise.all([A.open(), B.open()]);
    A.send({ type: "HELLO", playerId: "so-a", displayName: "Alice" });
    B.send({ type: "HELLO", playerId: "so-b", displayName: "Bob" });
    await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
    await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
    A.send({ type: "CREATE_CAMPAIGN", name: "中離團", campaignKey: "core", difficulty: "STANDARD" });
    const r = await A.waitFor(isRoster, "A roster");
    const cid = r.campaignId;
    B.send({ type: "JOIN_SESSION", campaignId: cid });
    await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
    A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
    await A.waitFor((m) => isRoster(m) && memberOf(m, "Alice").investigatorId === "joe_diamond", "A 選 joe");

    // B 中離 → 歸隊 → 再中離(驗證切換)
    B.send({ type: "SIT_OUT", sitOut: true });
    const sit1 = await B.waitFor((m) => isRoster(m) && memberOf(m, "Bob").status === "SITTING_OUT", "B 中離");
    check(memberOf(sit1, "Bob").status === "SITTING_OUT", "B 狀態 = SITTING_OUT");
    B.send({ type: "SIT_OUT", sitOut: false });
    await B.waitFor((m) => isRoster(m) && memberOf(m, "Bob").status === "ACTIVE", "B 歸隊");
    check(true, "中離/歸隊可切換");
    B.send({ type: "SIT_OUT", sitOut: true });
    await B.waitFor((m) => isRoster(m) && memberOf(m, "Bob").status === "SITTING_OUT", "B 再次中離");

    // 只剩 A(ACTIVE)按準備 → 屏障放行 → 1 人開打
    A.send({ type: "READY_DECK", ready: true });
    const st = await A.waitFor((m) => m.type === "STATE", "A 開打 STATE(1人)");
    check(st.view.round === 1 && st.view.you.investigatorId === "joe_diamond", "1 人開打:joe 第1輪");
    const fr = st.view.locations.find((l) => l.id === "friends_room");
    check(!!fr && fr.clues === 2, "friends_room 線索=2(clueValue2 × 1人:難度隨人數縮小)", `clues=${fr && fr.clues}`);
    check(st.view.otherInvestigators.length === 0, "場上只有 1 位調查員(B 未參戰)", `others=${st.view.otherInvestigators.length}`);
    A.close(); B.close();
  }
  await sleep(300);

  // ══════════ ② + ③ 接手(掉線重連)+ 重打掉線那一動 ══════════
  section("②③ 戰役中掉線 → 重連接手 → 補發卡住的投入決策");
  {
    const A = new Client("Alice");
    const B = new Client("Bob");
    await Promise.all([A.open(), B.open()]);
    A.send({ type: "HELLO", playerId: "tk-a", displayName: "Alice" });
    B.send({ type: "HELLO", playerId: "tk-b", displayName: "Bob" });
    await A.waitFor((m) => m.type === "LOBBY", "A LOBBY");
    await B.waitFor((m) => m.type === "LOBBY", "B LOBBY");
    A.send({ type: "CREATE_CAMPAIGN", name: "接手團", campaignKey: "core", difficulty: "STANDARD" });
    const r = await A.waitFor(isRoster, "A roster");
    const cid = r.campaignId;
    B.send({ type: "JOIN_SESSION", campaignId: cid });
    await B.waitFor((m) => isRoster(m) && m.members.length === 2, "B 加入");
    A.send({ type: "PICK_INVESTIGATOR", investigatorId: "joe_diamond" });
    await A.waitFor((m) => isRoster(m) && memberOf(m, "Alice").investigatorId === "joe_diamond", "A 選 joe");
    B.send({ type: "PICK_INVESTIGATOR", investigatorId: "daniela" });
    await B.waitFor((m) => isRoster(m) && memberOf(m, "Bob").investigatorId === "daniela", "B 選 daniela");
    A.send({ type: "READY_DECK", ready: true });
    B.send({ type: "READY_DECK", ready: true });
    await A.waitFor((m) => m.type === "STATE", "A 開打");
    await B.waitFor((m) => m.type === "STATE", "B 開打");

    // A 發起調查 → 開投入屏障(A 主檢定、B 同地點協助)
    A.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });
    await A.waitFor((m) => m.type === "CHOICE_REQUEST", "A 收到投入請求");
    const bReq = await B.waitFor((m) => m.type === "CHOICE_REQUEST", "B 收到投入請求");
    B.send({ type: "CHOICE_RESPONSE", requestId: bReq.requestId, choice: { committedCardIds: [] } });

    // A 在尚未回應時「掉線」(關閉連線)—— 屏障卡住等 A
    A.close();
    await sleep(600);

    // A 全新連線接手:HELLO(同 playerId)→ JOIN_SESSION → reattach
    const A2 = new Client("Alice#2");
    await A2.open();
    A2.send({ type: "HELLO", playerId: "tk-a", displayName: "Alice" });
    await A2.waitFor((m) => m.type === "LOBBY", "A2 LOBBY");
    A2.send({ type: "JOIN_SESSION", campaignId: cid });

    // 接手:先收回當前 STATE,並「重打掉線那一動」= 補發同一投入請求
    const stTake = await A2.waitFor((m) => m.type === "STATE", "A2 接回 STATE");
    check(stTake.view.you.investigatorId === "joe_diamond", "A2 接回 joe_diamond");
    const reReq = await A2.waitFor((m) => m.type === "CHOICE_REQUEST", "A2 補發卡住的投入請求(重打那一動)");
    check(reReq.kind === "COMMIT_CARDS", "補發的是同一個 COMMIT_CARDS 決策");
    // B 端應收到「接手」旁白
    const tkEvt = await B.waitFor((m) => m.type === "EVENT" && /接回|重新連線|接手/.test(m.message), "B 收到接手旁白");
    check(!!tkEvt, "隊友看到接手旁白", tkEvt && tkEvt.message);

    // A2 回應 → 屏障解除 → 檢定結算
    A2.send({ type: "CHOICE_RESPONSE", requestId: reReq.requestId, choice: { committedCardIds: [] } });
    const resolved = await A2.waitFor((m) => m.type === "STATE", "檢定結算後 STATE");
    check(resolved.view.you.actionsRemaining === 2, "接手後那一動完成(調查花掉行動 3→2)", `actions=${resolved.view.you.actionsRemaining}`);

    A2.close(); B.close();
  }
}

const hardTimeout = setTimeout(() => { console.error("\n✗✗ 動態名冊 e2e 整體逾時(90s)。"); process.exit(2); }, 90000);
main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 動態名冊 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 動態名冊(中離 / 難度縮放 / 接手 / 重打掉線那一動)e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => { clearTimeout(hardTimeout); console.error(`\n✗✗ 動態名冊 e2e 中止:${err.message}`); process.exit(2); });
