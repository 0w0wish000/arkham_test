import type {
  CampaignLogEntry, ApplyLogAction, SessionSummary, RosterMember, SessionRosterMsg, Difficulty, CampaignSave } from "../protocol";
import campaignsData from "../../../content/reference/campaigns.json";

/**
 * 大廳畫面(docs/09 P1):身分輸入 → 主選單(新檔/載入/離開)→ 名冊 waiting。
 * 純 DOM;透過 callback 把「建桌 / 加入 / 離桌 / 改名」交回給 main.ts → Connection。
 */

/** 戰役章節索引(build_campaigns.py 由卡資料推導;12 條主線 + 章節順序)。 */
const CAMPAIGNS = (campaignsData as {
  campaigns: { key: string; name: string; cycle: number; chapters: { name: string }[] }[];
}).campaigns;

const CAMPAIGN_ZH: Record<string, string> = {
  sandbox: "測試沙盒", core: "核心設定(舊)", core_2026: "2026 修訂核心",
  dunwich: "敦威治的遺產", carcosa: "卡爾克薩之路",
};
for (const c of CAMPAIGNS) {
  if (!CAMPAIGN_ZH[c.key]) CAMPAIGN_ZH[c.key] = c.name;
}
const DIFF_ZH: Record<string, string> = {
  EASY: "簡單", STANDARD: "標準", HARD: "困難", EXPERT: "專家",
};
const STAGE_ZH: Record<string, string> = {
  DECKBUILDING: "牌組編輯", IN_SCENARIO: "戰役中", LOADING: "載入中",
};
const STATUS_ZH: Record<string, string> = {
  ACTIVE: "", SITTING_OUT: "· 暫離", DEAD: "· 已陣亡",
};

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

type Screen = "identity" | "menu" | "roster" | "deckbuilder" | "game";

export class Lobby {
  onIdentity?: (name: string) => void;
  onRename?: (name: string) => void;
  onCreate?: (name: string, campaignKey: string, difficulty: Difficulty) => void;
  onJoin?: (campaignId: string) => void;
  onLeave?: () => void;
  // 牌組大廳(docs/09 §8.2)
  onPick?: (investigatorId: string) => void;
  onReady?: (ready: boolean) => void;
  onForceStart?: () => void;
  onOpenDeckbuilder?: () => void;   // 開完整選卡器(P2-2)
  // 加載存檔(docs/09 §7)
  onLoadSave?: (save: CampaignSave) => void;
  onReadyLoad?: (ready: boolean) => void;
  onRefreshSaves?: () => void;
  onDeleteSave?: (campaignId: string) => void;   // 刪除本機存檔(localStorage 有限)
  onSitOut?: (sitOut: boolean) => void;   // 中離/歸隊(docs/09 §9)
  onProposeNewChar?: (playerId: string) => void;   // 死亡換角投票(docs/09 §10)
  onClaimSeat?: (targetPlayerId: string) => void;  // 席位認領(docs/09 P6:換裝置回歸)
  onApplyLog?: (req: { action: ApplyLogAction; targetPlayerId?: string; cardName?: string;
    physicalDelta?: number; mentalDelta?: number; text?: string }) => void;   // 劇本指示(D7)

  private currentName = "";
  private myPlayerId = "";
  private myReady = false;
  private mySitOut = false;
  private curStage = "DECKBUILDING";   // 名冊畫面目前的 stage(決定 ready 按鈕行為)
  private $ = (id: string) => document.getElementById(id)!;
  private input = (id: string) => this.$(id) as HTMLInputElement;
  private select = (id: string) => this.$(id) as HTMLSelectElement;

  constructor() {
    // ── 身分 ──
    this.$("id-ok").onclick = () => this.submitIdentity();
    this.input("id-name").addEventListener("keydown", (e) => {
      if (e.key === "Enter") this.submitIdentity();
    });

    // ── 主選單 ──
    this.$("menu-new").onclick = () => {
      const f = this.$("new-form");
      f.hidden = !f.hidden;
      if (!f.hidden) this.input("new-name").focus();
    };
    this.$("menu-load").onclick = () => this.onRefreshSaves?.();   // 重新整理本機存檔清單
    this.$("menu-rename").onclick = () => {
      const name = prompt("改名(會記住,playerId 不變):", this.currentName);
      if (name && name.trim()) this.onRename?.(name.trim());
    };
    this.$("new-create").onclick = () => {
      const name = this.input("new-name").value.trim() || "未命名桌次";
      const campaignKey = this.select("new-campaign").value;
      const difficulty = this.select("new-diff").value as Difficulty;
      this.onCreate?.(name, campaignKey, difficulty);
    };

    // ── 戰役下拉:由 campaigns.json 動態生成(沙盒置頂;12 條主線含章節數)──
    const sel = this.select("new-campaign");
    sel.replaceChildren();
    const sandbox = document.createElement("option");
    sandbox.value = "sandbox";
    sandbox.textContent = "🧪 測試沙盒(試牌組 / 特殊卡)";
    sel.appendChild(sandbox);
    for (const c of [...CAMPAIGNS].sort((a, b) => b.cycle - a.cycle)) {   // 新的排前面
      const o = document.createElement("option");
      o.value = c.key;
      o.textContent = `${CAMPAIGN_ZH[c.key] ?? c.name}(${c.chapters.length} 章)`;
      sel.appendChild(o);
    }
    if (sel.options.length > 1) sel.selectedIndex = 1;   // 預設最新戰役(2026 修訂核心)

    // ── 戰役日誌 / 套用劇本指示(D6/D7)──
    const applyAction = this.select("apply-action");
    const refreshApplyRows = () => {
      const a = applyAction.value;
      (this.$("apply-target") as HTMLElement).hidden = a === "RECORD";
      (this.$("apply-card-row") as HTMLElement).hidden = !(a === "ADD_CARD" || a === "REMOVE_CARD");
      (this.$("apply-trauma-row") as HTMLElement).hidden = a !== "ADJUST_TRAUMA";
      (this.$("apply-text-row") as HTMLElement).hidden = a !== "RECORD";
    };
    applyAction.onchange = refreshApplyRows;
    refreshApplyRows();
    this.$("apply-go").onclick = () => {
      const action = applyAction.value as ApplyLogAction;
      const target = this.select("apply-target").value || undefined;
      const cardName = this.input("apply-card").value.trim() || undefined;
      const text = this.input("apply-text").value.trim() || undefined;
      const physicalDelta = Number(this.select("apply-phys").value) || 0;
      const mentalDelta = Number(this.select("apply-ment").value) || 0;
      const desc = action === "RECORD" ? `記事:「${text ?? ""}」`
        : action === "ADD_CARD" ? `把「${cardName ?? ""}」加進所選隊員牌組`
        : action === "REMOVE_CARD" ? `從所選隊員牌組移除「${cardName ?? ""}」`
        : `調整所選隊員創傷(🩸${physicalDelta >= 0 ? "+" : ""}${physicalDelta} / 🧠${mentalDelta >= 0 ? "+" : ""}${mentalDelta})`;
      if (!confirm(`套用劇本指示 —— ${desc}?\n(全隊同步、寫入戰役日誌並自動存檔)`)) return;
      this.onApplyLog?.({ action, targetPlayerId: target, cardName, physicalDelta, mentalDelta, text });
      this.input("apply-card").value = "";
      this.input("apply-text").value = "";
      this.select("apply-phys").value = "0";
      this.select("apply-ment").value = "0";
    };

    // ── 名冊 / 牌組大廳 ──
    this.$("roster-leave").onclick = () => this.onLeave?.();
    this.select("deck-inv").onchange = () => {
      const v = this.select("deck-inv").value;
      if (v) this.onPick?.(v);
    };
    this.$("deck-ready").onclick = () =>
      this.curStage === "LOADING" ? this.onReadyLoad?.(!this.myReady) : this.onReady?.(!this.myReady);
    this.$("deck-force").onclick = () => this.onForceStart?.();
    this.$("deck-open").onclick = () => this.onOpenDeckbuilder?.();
    this.$("deck-sitout").onclick = () => this.onSitOut?.(!this.mySitOut);
  }

  /** 目前(下拉)已選的調查員 id,若無則空字串 —— 給選卡器帶入/鎖定用。 */
  currentPick(): string { return this.select("deck-inv").value; }

  /** 讓大廳知道「我」是誰(用來在名冊中標出自己、同步 ready 狀態)。 */
  setPlayer(playerId: string) { this.myPlayerId = playerId; }

  private submitIdentity() {
    const name = this.input("id-name").value.trim();
    if (name) this.onIdentity?.(name);
  }

  show(screen: Screen) {
    (["identity", "menu", "roster", "deckbuilder", "game"] as Screen[]).forEach((s) => {
      (this.$(`screen-${s}`) as HTMLElement).hidden = s !== screen;
    });
  }

  showIdentity(prefill = "") {
    this.input("id-name").value = prefill;
    this.show("identity");
    this.input("id-name").focus();
  }

  showMenu(displayName: string) {
    this.currentName = displayName;
    this.$("menu-who").textContent = displayName;
    this.$("new-form").hidden = true;
    this.show("menu");
  }

  /** 進行中桌次清單(LOBBY)。 */
  renderLobby(sessions: SessionSummary[]) {
    const box = this.$("session-list");
    box.replaceChildren();
    if (sessions.length === 0) {
      box.appendChild(el("div", "empty", "目前沒有進行中的桌次"));
      return;
    }
    for (const s of sessions) box.appendChild(this.sessionRow(s));
  }

  private sessionRow(s: SessionSummary): HTMLElement {
    const row = el("div", "sess");
    const left = el("div");
    const main = el("div", "s-main");
    main.appendChild(document.createTextNode(s.name));
    main.appendChild(el("span", "s-tag", STAGE_ZH[s.stage] ?? s.stage));
    left.appendChild(main);
    const who = s.members.map((m) => m.displayName).join("、") || "(空桌)";
    left.appendChild(el("div", "s-sub",
      `${CAMPAIGN_ZH[s.campaignKey] ?? s.campaignKey} · ${DIFF_ZH[s.difficulty] ?? s.difficulty} · ${s.memberCount} 人:${who}`));
    row.appendChild(left);
    const btn = el("button", undefined, "加入");
    btn.onclick = () => this.onJoin?.(s.campaignId);
    row.appendChild(btn);
    return row;
  }

  /** 本機存檔清單(主選單「加載續玩」)。 */
  renderSaves(saves: CampaignSave[]) {
    const box = this.$("local-saves");
    box.replaceChildren();
    if (saves.length === 0) { box.appendChild(el("div", "empty", "本機還沒有存檔")); return; }
    for (const s of saves) box.appendChild(this.saveRow(s));
  }

  private saveRow(s: CampaignSave): HTMLElement {
    const row = el("div", "sess");
    const left = el("div");
    const main = el("div", "s-main");
    main.appendChild(document.createTextNode(s.name));
    main.appendChild(el("span", "s-tag", STAGE_ZH[s.stage] ?? s.stage));
    left.appendChild(main);
    const where = s.stage === "IN_SCENARIO" ? `第 ${s.round} 輪` : "牌組編輯";
    const who = s.roster.map((m) => m.displayName).join("、");
    left.appendChild(el("div", "s-sub",
      `${CAMPAIGN_ZH[s.campaignKey] ?? s.campaignKey} · ${DIFF_ZH[s.difficulty] ?? s.difficulty} · ${where} · ${who}`));
    row.appendChild(left);
    const btn = el("button", undefined, "載入");
    btn.onclick = () => this.onLoadSave?.(s);
    row.appendChild(btn);
    const del = el("button", "s-del", "🗑");
    del.title = "刪除這份本機存檔(不影響其他玩家的備份)";
    del.onclick = () => {
      if (confirm(`刪除本機存檔「${s.name}」?(其他玩家本機的備份不受影響)`)) this.onDeleteSave?.(s.campaignId);
    };
    row.appendChild(del);
    return row;
  }

  /** 名冊 waiting 畫面(SESSION_ROSTER):牌組大廳(DECKBUILDING)或載入等待(LOADING)。 */
  renderRoster(msg: SessionRosterMsg) {
    this.curStage = msg.stage;
    const loading = msg.stage === "LOADING";
    this.$("roster-name").textContent = msg.name;
    const readyCount = msg.members.filter((m) => m.ready).length;
    const camp = CAMPAIGNS.find((c) => c.key === msg.campaignKey);
    const chap = msg.currentChapter && camp
      ? ` · 第 ${msg.currentChapter}/${camp.chapters.length} 章${camp.chapters[msg.currentChapter - 1] ? "「" + camp.chapters[msg.currentChapter - 1].name + "」" : ""}`
      : msg.currentChapter ? ` · 第 ${msg.currentChapter} 章` : "";
    this.$("roster-meta").textContent =
      `${CAMPAIGN_ZH[msg.campaignKey] ?? msg.campaignKey} · 難度 ${DIFF_ZH[msg.difficulty] ?? msg.difficulty}` +
      chap + ` · ${STAGE_ZH[msg.stage] ?? msg.stage} · 就緒 ${readyCount}/${msg.members.length}`;

    const list = this.$("roster-list");
    list.replaceChildren();
    const targetSel = this.select("apply-target");
    const prevTarget = targetSel.value;
    targetSel.replaceChildren();
    for (const m of msg.members) {
      const o = document.createElement("option");
      o.value = m.playerId;
      o.textContent = `${m.displayName}${m.investigatorId ? "(" + m.investigatorId + ")" : ""}`;
      targetSel.appendChild(o);
    }
    if ([...targetSel.options].some((o) => o.value === prevTarget)) targetSel.value = prevTarget;
    const meNow = msg.members.find((x) => x.playerId === this.myPlayerId);
    // 我尚未有角色時,可對「離線且已選角」的席位發起認領(換裝置回歸的自己)
    const canClaim = !!meNow && !meNow.investigatorId;
    for (const m of msg.members) list.appendChild(this.memberRow(m, loading, canClaim));

    // 牌組控制(選角/選卡器)只在 DECKBUILDING 顯示;LOADING 只需「我已就緒」
    const invField = this.$("deck-inv").closest(".field") as HTMLElement | null;
    if (invField) invField.hidden = loading;
    (this.$("deck-open") as HTMLElement).hidden = loading;
    (this.$("deck-sitout") as HTMLElement).hidden = loading;

    const mine = msg.members.find((m) => m.playerId === this.myPlayerId);
    this.mySitOut = mine?.status === "SITTING_OUT";
    if (!loading) {
      const inv = this.select("deck-inv");
      inv.value = mine?.investigatorId ?? "";
      inv.disabled = this.mySitOut;
      const dead = new Set(msg.deadInvestigators ?? []);
      const takenByOthers = new Set(
        msg.members.filter((m) => m.playerId !== this.myPlayerId && m.investigatorId).map((m) => m.investigatorId));
      for (const opt of Array.from(inv.options)) {
        if (opt.value) opt.disabled = takenByOthers.has(opt.value) || dead.has(opt.value);
      }
      (this.$("deck-open") as HTMLButtonElement).disabled = this.mySitOut;
      this.$("deck-sitout").textContent = this.mySitOut ? "↩️ 歸隊" : "💤 暫時脫離";
    }

    this.myReady = mine?.ready ?? false;
    const readyBtn = this.$("deck-ready") as HTMLButtonElement;
    readyBtn.textContent = loading
      ? (this.myReady ? "✗ 取消" : "✓ 我已就緒(載入續玩)")
      : (this.myReady ? "✗ 取消準備" : "✓ 準備完成");
    readyBtn.classList.toggle("on", this.myReady);
    readyBtn.disabled = !loading && this.mySitOut;   // 中離時不能按準備
    (this.$("deck-force") as HTMLButtonElement).hidden = !msg.canForce;
    this.$("roster-hint").textContent = loading
      ? "本機已載入存檔;等隊友都就緒即續玩(主機可「強制開始」)。"
      : "全員選好角色並按「準備完成」即自動開打。";

    this.show("roster");
  }

  private memberRow(m: RosterMember, loading: boolean, canClaim = false): HTMLElement {
    const row = el("div", "m");
    const dot = el("span", "dot");
    if (m.connected === false) dot.style.background = "#4a5563";        // 離線:深灰
    else if (m.status === "DEAD") dot.style.background = "#8a3b2f";
    else if (m.status === "SITTING_OUT") dot.style.background = "#93a4b3";
    row.appendChild(dot);
    row.appendChild(el("span", "m-name", m.displayName));
    const trauma = (m.physicalTrauma > 0 ? ` 🩸創傷${m.physicalTrauma}` : "")
      + (m.mentalTrauma > 0 ? ` 🧠創傷${m.mentalTrauma}` : "");
    const role = (m.investigatorId ?? "尚未選角") + trauma
      + (STATUS_ZH[m.status] ? " " + STATUS_ZH[m.status] : "")
      + (m.connected === false ? "(離線)" : "");
    row.appendChild(el("span", "m-role", role));
    if (m.ready) row.appendChild(el("span", "m-ready", "✓ 就緒"));
    // 席位認領(P6):我還沒角色 + 對方離線 → 「這是我」認回席位(繼承角色/牌組/XP)
    if (canClaim && m.connected === false && m.playerId !== this.myPlayerId) {
      const c = el("button", "m-dead", "🔑 這是我");
      c.title = "換了裝置?認領這個離線席位,繼承其調查員/牌組/經驗(需在線隊友同意)";
      c.onclick = () => {
        if (confirm(`認領「${m.displayName}」的席位?通過後你將繼承其角色、牌組與經驗。`)) {
          this.onClaimSeat?.(m.playerId);
        }
      };
      row.appendChild(c);
    }
    // 牌組大廳:對「已選角的成員」可發起死亡換角投票(其角色陣亡時)
    if (!loading && m.investigatorId) {
      const b = el("button", "m-dead", "☠ 換角");
      b.title = "此角色陣亡 → 發起換角投票(通過後該角色永久封鎖)";
      b.onclick = () => this.onProposeNewChar?.(m.playerId);
      row.appendChild(b);
    }
    return row;
  }

  /** 戰役日誌全量渲染(D6:CAMPAIGN_LOG)。 */
  renderLog(entries: CampaignLogEntry[]) {
    const box = this.$("camp-log");
    box.replaceChildren();
    box.classList.toggle("empty", entries.length === 0);
    if (entries.length === 0) {
      box.textContent = "尚無日誌 —— 劇本要你加卡/移卡/調創傷/記旗標時,在這裡套用,全隊同步並自動存檔。";
      return;
    }
    for (const e of entries) {
      const row = el("div", "cl");
      row.appendChild(el("span", "cl-ch", `第${e.chapter}章`));
      row.appendChild(document.createTextNode(e.text));
      row.appendChild(el("span", "cl-by", `— ${e.by}`));
      box.appendChild(row);
    }
    box.scrollTop = box.scrollHeight;
  }

  /** 大廳旁白事件(加入/脫離等)。 */
  logEvent(msg: string) {
    const box = this.$("lobby-log");
    box.appendChild(el("div", undefined, msg));
    box.scrollTop = box.scrollHeight;
  }
}
