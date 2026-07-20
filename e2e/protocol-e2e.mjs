// ════════════════════════════════════════════════════════════════════
//  Arkham 端到端測試(協定 / 遊戲流程層)
//  以兩個真實 WebSocket 客戶端連向真正的 Java 伺服器,走完整協定:
//    JOIN(joe+daniela) → INVESTIGATE(多人投入屏障) → MOVE(揭示+生怪)
//      → END_TURN(回合推進) → SAVE(全員投票) → RESUME(重建對局)
//  只用 Node 內建 WebSocket(需 Node 22+),零相依。
//
//  用法:  node e2e/protocol-e2e.mjs [ws://host:8080]
//  離開碼:0=全過,1=有斷言失敗,2=連線/流程逾時
// ════════════════════════════════════════════════════════════════════

const URL = process.argv[2] || process.env.ARKHAM_WS || "ws://localhost:8080";
const WS = `${URL.replace(/\/$/, "")}/ws/game`;
// 每次跑用不同房號,避免沿用同房舊狀態(房號決定亂數種子,流程仍可重現)
const ROOM = process.env.ARKHAM_ROOM || `e2e-${process.pid}`;

let passed = 0, failed = 0;
const fails = [];
function check(cond, msg, detail) {
  if (cond) { passed++; console.log(`  ✓ ${msg}`); }
  else { failed++; fails.push(msg); console.error(`  ✗ ${msg}${detail ? "  →  " + detail : ""}`); }
}
function section(t) { console.log(`\n── ${t} ──`); }

/** 一個測試用客戶端:訊息進 queue,waitFor(pred) 消費第一則符合的訊息(含未來到達的)。 */
class Client {
  constructor(name) {
    this.name = name;
    this.q = [];
    this.waiters = [];
    this.ws = null;
  }
  _wire(ws) {
    ws.addEventListener("message", (ev) => { this.q.push(JSON.parse(ev.data)); this._pump(); });
  }
  _connectOnce() {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(WS);
      let settled = false;
      ws.addEventListener("open", () => { if (settled) return; settled = true; this.ws = ws; this._wire(ws); resolve(); });
      ws.addEventListener("error", () => { if (settled) return; settled = true; reject(new Error(`[${this.name}] WebSocket 連線失敗`)); });
      ws.addEventListener("close", () => { if (settled) return; settled = true; reject(new Error(`[${this.name}] WebSocket 連線被關閉`)); });
    });
  }
  /** 連線(含重試):伺服器剛起或短暫忙碌時,重試幾次而非直接失敗。 */
  async open(attempts = 5) {
    for (let i = 1; i <= attempts; i++) {
      try { await this._connectOnce(); return; }
      catch (e) { if (i === attempts) throw new Error(e.message + "(已重試 " + attempts + " 次,伺服器沒起來?)"); await new Promise((r) => setTimeout(r, 600)); }
    }
  }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  _pump() {
    for (const w of this.waiters) {
      if (w.done) continue;
      const idx = this.q.findIndex(w.pred);
      if (idx >= 0) {
        const [m] = this.q.splice(idx, 1);
        w.done = true; clearTimeout(w.timer); w.resolve(m);
      }
    }
    this.waiters = this.waiters.filter((w) => !w.done);
  }
  waitFor(pred, label = "訊息", timeout = 12000) {
    return new Promise((resolve, reject) => {
      const w = { pred, resolve, done: false };
      w.timer = setTimeout(() => {
        w.done = true;
        this.waiters = this.waiters.filter((x) => x !== w);
        const recent = this.q.slice(-8).map((x) => x.type + (x.event ? `(${x.event})` : ""));
        reject(new Error(`[${this.name}] 等不到「${label}」(逾時)。近期收到:[${recent.join(", ")}]`));
      }, timeout);
      this.waiters.push(w);
      this._pump();
    });
  }
  close() { try { this.ws.close(); } catch { /* ignore */ } }
}

const isState = (m) => m.type === "STATE";
const isChoice = (m) => m.type === "CHOICE_REQUEST";

async function main() {
  console.log(`▶ e2e 連線 ${WS}  房間「${ROOM}」`);
  const joe = new Client("joe");
  const dani = new Client("daniela");
  await Promise.all([joe.open(), dani.open()]);

  // ── 1. JOIN:兩位調查員入房,各自收到初始過濾視圖 ──
  section("1. JOIN / 初始狀態");
  joe.send({ type: "JOIN", sessionId: ROOM, investigatorId: "joe_diamond" });
  dani.send({ type: "JOIN", sessionId: ROOM, investigatorId: "daniela" });
  const js0 = await joe.waitFor(isState, "joe 初始 STATE");
  const ds0 = await dani.waitFor(isState, "daniela 初始 STATE");

  check(js0.view.round === 1, "回合為第 1 輪", `round=${js0.view.round}`);
  check(js0.view.phase === "INVESTIGATION", "階段為 INVESTIGATION", `phase=${js0.view.phase}`);
  check(js0.view.you.investigatorId === "joe_diamond", "joe 視圖=joe_diamond");
  check(js0.view.you.locationId === "friends_room", "joe 起始於 friends_room", js0.view.you.locationId);
  check(js0.view.you.actionsRemaining === 3, "joe 起始 3 個行動", `actions=${js0.view.you.actionsRemaining}`);
  check(ds0.view.you.investigatorId === "daniela", "daniela 視圖=daniela(每客戶端過濾)");
  const fr = js0.view.locations.find((l) => l.id === "friends_room");
  check(!!fr && fr.clues === 4, "friends_room 有 4 線索(clueValue2 × 2人)", `clues=${fr?.clues}`);
  check(!!fr && fr.shroud === 2, "friends_room 遮蔽 2", `shroud=${fr?.shroud}`);
  // joe 看不到 daniela 的手牌內容(只給 handCount)
  const daniAsSeenByJoe = js0.view.otherInvestigators.find((o) => o.investigatorId === "daniela");
  check(!!daniAsSeenByJoe && daniAsSeenByJoe.hand === undefined, "joe 看不到 daniela 的手牌內容(隱藏資訊)");

  // ── 2. INVESTIGATE:開技能檢定 → 多人投入屏障(joe 主檢定 + daniela 同地點可協助) ──
  section("2. INVESTIGATE / 多人投入屏障");
  joe.send({ type: "INTENT", action: "INVESTIGATE", payload: {} });
  const jReq = await joe.waitFor(isChoice, "joe 的 COMMIT_CARDS 請求");
  const dReq = await dani.waitFor(isChoice, "daniela 的 COMMIT_CARDS 請求");
  check(jReq.kind === "COMMIT_CARDS", "joe 收到 COMMIT_CARDS", jReq.kind);
  check(dReq.kind === "COMMIT_CARDS", "daniela 也被要求投入(屏障對同地點隊友發送)", dReq.kind);
  check(jReq.options.skill === "INTELLECT", "檢定技能=INTELLECT", jReq.options?.skill);
  check(jReq.options.difficulty === 2, "難度=遮蔽 2", `difficulty=${jReq.options?.difficulty}`);
  check(dReq.options.maxCommit === 1, "隊友最多投入 1 張(協助上限)", `maxCommit=${dReq.options?.maxCommit}`);
  // 兩人都不投入(送空)—— 檢定仍以基礎技能結算
  joe.send({ type: "CHOICE_RESPONSE", requestId: jReq.requestId, choice: { committedCardIds: [] } });
  dani.send({ type: "CHOICE_RESPONSE", requestId: dReq.requestId, choice: { committedCardIds: [] } });
  const js1 = await joe.waitFor(isState, "檢定結算後 STATE");
  check(js1.view.you.actionsRemaining === 2, "檢定花掉 1 個行動(3→2,不論成敗)", `actions=${js1.view.you.actionsRemaining}`);
  // 若調查成功會觸發 Joe 的反應能力(能力引擎)→ 這裡略過,免得擋住後續意圖
  try {
    const opt = await joe.waitFor((m) => m.type === "CHOICE_REQUEST" && m.kind === "CHOOSE_OPTION", "反應詢問", 700);
    joe.send({ type: "CHOICE_RESPONSE", requestId: opt.requestId, choice: { optionId: "skip" } });
    await joe.waitFor(isState, "略過反應後 STATE");
  } catch { /* 檢定失敗 → 沒有詢問,不需處理 */ }

  // ── 3. MOVE:走到 dormitories → 揭示 + 生怪(Servant)並交戰 ──
  section("3. MOVE / 揭示與生怪");
  joe.send({ type: "INTENT", action: "MOVE", payload: { toLocationId: "dormitories" } });
  const js2 = await joe.waitFor((m) => isState(m) && m.view.you.locationId === "dormitories", "移動後 STATE");
  check(js2.view.you.locationId === "dormitories", "joe 已在 dormitories");
  const dorm = js2.view.locations.find((l) => l.id === "dormitories");
  check(!!dorm && dorm.revealed === true, "dormitories 已揭示");
  const servant = js2.view.enemies.find((e) => e.name.includes("Servant"));
  check(!!servant, "Servant 已生成", `enemies=${js2.view.enemies.map((e) => e.name).join("/") || "無"}`);
  check(!!servant && servant.engagedWith === "joe_diamond", "Servant 與 joe 交戰");
  check(js2.view.you.actionsRemaining === 1, "移動再花 1 個行動(2→1)", `actions=${js2.view.you.actionsRemaining}`);

  // ── 4. END_TURN 屏障:一人結束不推進;全員完成才走敵人/整備/神話 → 第 2 輪 ──
  section("4. END_TURN 屏障 / 回合推進");
  joe.send({ type: "INTENT", action: "END_TURN", payload: {} });
  const jDone = await joe.waitFor((m) => isState(m) && m.view.you.turnDone === true, "joe 標記已結束");
  check(jDone.view.round === 1, "joe 一人結束 → 仍第 1 輪(屏障擋住)", `round=${jDone.view.round}`);
  dani.send({ type: "INTENT", action: "END_TURN", payload: {} });
  const js3 = await joe.waitFor((m) => isState(m) && m.view.round === 2, "進入第 2 輪的 STATE");
  check(js3.view.round === 2, "回合推進到第 2 輪", `round=${js3.view.round}`);
  check(js3.view.phase === "INVESTIGATION", "回到 INVESTIGATION 階段", js3.view.phase);
  check(js3.view.you.actionsRemaining === 3, "新回合行動重置為 3", `actions=${js3.view.you.actionsRemaining}`);

  // ── 5. SAVE:joe 發起 → 兩人各收彈窗 → 都同意 → 兩人各收快照(複製本機) ──
  section("5. SAVE / 全員投票存檔");
  joe.send({ type: "SAVE_REQUEST" });
  const jPrompt = await joe.waitFor((m) => m.type === "SAVE_PROMPT", "joe 的存檔彈窗");
  const dPrompt = await dani.waitFor((m) => m.type === "SAVE_PROMPT", "daniela 的存檔彈窗");
  check(!!jPrompt && !!dPrompt, "兩位玩家都收到 SAVE_PROMPT");
  joe.send({ type: "SAVE_VOTE", requestId: jPrompt.requestId, vote: true });
  dani.send({ type: "SAVE_VOTE", requestId: dPrompt.requestId, vote: true });
  const jSnap = await joe.waitFor((m) => m.type === "SAVE_SNAPSHOT", "joe 的存檔快照");
  const dSnap = await dani.waitFor((m) => m.type === "SAVE_SNAPSHOT", "daniela 的存檔快照");
  check(jSnap.round === 2, "快照回合=2", `round=${jSnap.round}`);
  check(!!jSnap.state, "快照含完整 state 文本");
  check(Array.isArray(jSnap.eventLog) && jSnap.eventLog.length > 0, "快照含出牌/事件紀錄 eventLog", `len=${jSnap.eventLog?.length}`);
  check(!!dSnap.state, "daniela 也收到同一份快照(複製到各本機)");

  // ── 6. RESUME:把快照 state 送回伺服器重建對局 → 狀態應還原(joe 仍在 dormitories) ──
  section("6. RESUME / 重開載入還原");
  joe.send({ type: "RESUME", state: jSnap.state });
  const jResumeEvt = await joe.waitFor((m) => m.type === "EVENT" && m.event === "resume", "resume 事件");
  check(!!jResumeEvt, "收到 resume 事件", jResumeEvt?.message);
  const js4 = await joe.waitFor((m) => isState(m) && m.view.round === 2, "重建後 STATE(第 2 輪)");
  check(js4.view.round === 2, "續玩後回到第 2 輪", `round=${js4.view.round}`);
  check(js4.view.you.locationId === "dormitories", "續玩還原了移動後位置(非重置回 friends_room)", js4.view.you.locationId);
  const dorm2 = js4.view.locations.find((l) => l.id === "dormitories");
  check(!!dorm2 && dorm2.revealed === true, "續玩還原了已揭示的 dormitories");

  joe.close(); dani.close();
}

const hardTimeout = setTimeout(() => {
  console.error("\n✗✗ 整體逾時(90s)—— 伺服器可能沒起來或流程卡住。");
  process.exit(2);
}, 90000);

main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log(`\n═══ 協定 e2e 結果:${passed} 通過 / ${failed} 失敗 ═══`);
    if (failed > 0) { console.error("失敗項:\n - " + fails.join("\n - ")); process.exit(1); }
    console.log("✓ 協定 / 遊戲流程 e2e 全數通過。");
    process.exit(0);
  })
  .catch((err) => {
    clearTimeout(hardTimeout);
    console.error(`\n✗✗ e2e 執行中止:${err.message}`);
    process.exit(2);
  });
