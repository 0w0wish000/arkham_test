import type {
  GameStateView, EnemyView, HandCard, LocationView,
  SkillType, SkillIcon, IntentAction, CommitCardsOptions, ChooseTargetOptions,
} from "../protocol";
import { confirmDialog } from "./dialogs";

/**
 * DOM 抬頭顯示(HUD):把伺服器下發的 GameStateView 畫成可操作的介面 ——
 * 你的狀態、所在地點的敵人(戰鬥/閃避/交戰)、手牌、行動列(調查/結束回合/推進幕/存檔),
 * 以及最關鍵的「多人技能檢定投入面板」(伺服器發 COMMIT_CARDS 時彈出)。
 *
 * 純呈現 + 送意圖:所有規則判定都在 Java 引擎;非法操作伺服器會回 ERROR(顯示在 log)。
 * PixiJS 地圖(GameView)仍負責「點相鄰地點 → 移動」。
 */

const INV_NAME: Record<string, string> = {
  joe_diamond: "Joe Diamond", daniela: "Daniela Reyes",
};
const SKILL_ZH: Record<SkillType, string> = {
  WILLPOWER: "意志", INTELLECT: "智力", COMBAT: "戰鬥", AGILITY: "敏捷",
};
const PHASE_ZH: Record<string, string> = {
  MYTHOS: "神話階段", INVESTIGATION: "調查階段", ENEMY: "敵人階段", UPKEEP: "整備階段",
};
const ICON: Record<SkillIcon, { ch: string; color: string }> = {
  WILLPOWER: { ch: "意", color: "#6fa8ff" },
  INTELLECT: { ch: "智", color: "#e8c14b" },
  COMBAT: { ch: "戰", color: "#e0674b" },
  AGILITY: { ch: "敏", color: "#5fbf6f" },
  WILD: { ch: "✦", color: "#c9a24b" },
};

/** 一張卡對某技能的貢獻 = 符合該技能或 WILD 的圖示數。 */
function matchingIcons(card: HandCard, skill: SkillType): number {
  return card.skillIcons.filter((i) => i === skill || i === "WILD").length;
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function iconEl(icon: SkillIcon): HTMLElement {
  const s = el("span", "ic", ICON[icon].ch);
  s.style.color = ICON[icon].color;
  return s;
}

/** 卡圖(玩家自帶 /cardimg/<slug>.<ext>,slug=卡名+_l0):探測一次即快取;miss → 色塊占位。 */
const IMG_CACHE = new Map<string, string | null>();
function cardSlug(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "") + "_l0";
}
function probeCardImage(name: string, onFound: (url: string) => void): void {
  const slug = cardSlug(name);
  if (IMG_CACHE.has(slug)) { const u = IMG_CACHE.get(slug); if (u) onFound(u); return; }
  const exts = ["webp", "png", "jpg"]; let i = 0;
  const tryNext = () => {
    if (i >= exts.length) { IMG_CACHE.set(slug, null); return; }
    const url = `/cardimg/${slug}.${exts[i++]}`;
    const im = new Image();
    im.onload = () => { IMG_CACHE.set(slug, url); onFound(url); };
    im.onerror = tryNext;
    im.src = url;
  };
  tryNext();
}
const TYPE_FALLBACK_TEXT: Record<string, string> = {
  skill: "技能卡:於檢定投入面板使用(圖示=投入點數)。",
  asset: "支援卡:打出後留在檯面持續生效。",
  event: "事件卡:打出立即生效,然後棄掉。",
  weakness: "弱點:留在手上(完整規則後續實作)。",
};
const TYPE_COLOR: Record<string, string> = {
  asset: "#2d5a3a", event: "#2d4a63", skill: "#8a6d2f", weakness: "#8a3b2f",
};

/** 混沌標記 → /tokens/<name>.svg(自繪預設圖組;認不得 → null 用文字面)。 */
function tokenImageFor(desc: string): string | null {
  if (desc.includes("骷髏")) return "skull";
  if (desc.includes("異教徒")) return "cultist";
  if (desc.includes("石板")) return "tablet";
  if (desc.includes("遠古")) return "elder_thing";
  if (desc.includes("古老印記")) return "elder_sign";
  if (desc.includes("自動失敗")) return "autofail";
  const n = desc.match(/^([+-]?\d+)/);
  if (n) {
    const v = parseInt(n[1], 10);
    return v > 0 ? `plus${v}` : v === 0 ? "zero" : `minus${-v}`;
  }
  return null;
}

/** 卡片簡述:真卡/翻譯文字(去標記)→ 卡型通用說明。 */
function cardDesc(c: HandCard): string {
  return (c.text ?? "").replace(/<[^>]+>/g, "").replace(/\[\[|\]\]/g, "").trim()
      || TYPE_FALLBACK_TEXT[c.cardType] || "";
}

/** 懸浮預覽卡(手牌/檯面共用):大縮圖 + 名稱/費用/圖示 + 完整文字。 */
function showCardPreview(c: HandCard, anchor: DOMRect) {
  hideCardPreview();
  const p = el("div");
  p.id = "card-preview";
  const art = el("div", "cp-art");
  art.style.background = TYPE_COLOR[c.cardType] ?? "#3a4b5c";
  probeCardImage(c.name, (url) => { art.style.background = `url('${url}') center/cover`; });
  p.appendChild(art);
  const title = el("div", "cp-name");
  if (c.cardType === "asset" || c.cardType === "event") title.appendChild(el("span", "cost", `$${c.cost}`));
  title.appendChild(document.createTextNode(c.name));
  if (c.skillIcons.length) {
    const icons = el("span", "icons");
    for (const ic of c.skillIcons) icons.appendChild(iconEl(ic));
    title.appendChild(icons);
  }
  p.appendChild(title);
  p.appendChild(el("div", "cp-text", cardDesc(c)));
  placePreview(p, anchor);
}

/** 預覽面板定位:優先錨點左側;放不下換右側,並夾在視窗內。 */
function placePreview(p: HTMLElement, anchor: DOMRect) {
  document.body.appendChild(p);
  const w = p.offsetWidth, h = p.offsetHeight;
  let x = anchor.left - w - 10;
  if (x < 8) x = Math.min(anchor.right + 10, innerWidth - w - 8);
  const y = Math.max(8, Math.min(anchor.top, innerHeight - h - 8));
  p.style.left = `${x}px`;
  p.style.top = `${y}px`;
}

/** 敵人詳細視窗(比照手牌 hover):數值全覽 + 關鍵字 + 卡片文字(真卡/翻譯有就顯示)。 */
function showEnemyPreview(e: EnemyView, anchor: DOMRect) {
  hideCardPreview();
  const p = el("div");
  p.id = "card-preview";
  const art = el("div", "cp-art");
  art.style.background = "#4a2620";
  probeCardImage(e.name, (url) => { art.style.background = `url('${url}') center/cover`; });
  p.appendChild(art);
  const title = el("div", "cp-name", e.name);
  p.appendChild(title);
  const lines = [
    `戰鬥 ${e.fight} · 閃避 ${e.evade} · 生命 ${e.health - e.damageOn}/${e.health}`,
    `攻擊:${e.damage} 傷害 / ${e.horror} 恐懼`,
  ];
  if (e.keywords.length) lines.push("關鍵字:" + e.keywords.join("、"));
  const text = (e.text ?? "").replace(/<[^>]+>/g, "").replace(/\[\[|\]\]/g, "").trim();
  if (text) lines.push("", text);
  p.appendChild(el("div", "cp-text", lines.join("\n")));
  placePreview(p, anchor);
}
function hideCardPreview() { document.getElementById("card-preview")?.remove(); }
function attachPreview(elm: HTMLElement, c: HandCard) {
  elm.addEventListener("mouseenter", () => showCardPreview(c, elm.getBoundingClientRect()));
  elm.addEventListener("mouseleave", hideCardPreview);
}

/** 手牌直列列項:卡圖(或色塊)+ 名稱/費用/圖示 + 簡述(截 3 行,hover 大圖預覽)。 */
function handRow(c: HandCard): HTMLDivElement {
  const row = el("div", "hrow");
  const thumb = el("div", "hthumb");
  thumb.style.background = TYPE_COLOR[c.cardType] ?? "#3a4b5c";
  probeCardImage(c.name, (url) => { thumb.style.background = `url('${url}') center/cover`; });
  row.appendChild(thumb);
  const main = el("div", "hmain");
  const title = el("div", "hname");
  if (c.cardType === "asset" || c.cardType === "event") title.appendChild(el("span", "cost", `$${c.cost}`));
  title.appendChild(document.createTextNode(c.name));
  if (c.skillIcons.length) {
    const icons = el("span", "icons");
    for (const ic of c.skillIcons) icons.appendChild(iconEl(ic));
    title.appendChild(icons);
  }
  main.appendChild(title);
  main.appendChild(el("div", "htext", cardDesc(c)));
  row.appendChild(main);
  attachPreview(row, c);
  return row;
}

function cardChip(c: HandCard): HTMLDivElement {
  const d = el("div", "card");
  const name = el("span", "c-name");
  if (c.cardType === "asset" || c.cardType === "event") name.appendChild(el("span", "cost", `$${c.cost}`));  // 費用
  name.appendChild(document.createTextNode(c.name));
  d.appendChild(name);
  const icons = el("span", "icons");
  for (const i of c.skillIcons) icons.appendChild(iconEl(i));
  d.appendChild(icons);
  return d;
}

type CommitState = { requestId: string; opts: CommitCardsOptions; sel: Set<string> };

export class Hud {
  /** 送出行動意圖(對應 protocol INTENT.action)。 */
  onIntent?: (action: IntentAction, payload?: Record<string, unknown>) => void;
  /** 回應技能檢定投入(CHOICE_RESPONSE)。 */
  onCommit?: (requestId: string, committedCardIds: string[]) => void;
  /** 回應超限棄牌(CHOICE_RESPONSE.targetIds;B6)。 */
  onDiscard?: (requestId: string, targetIds: string[]) => void;
  /** 按下「保存並離開」。 */
  onSave?: () => void;

  private view?: GameStateView;
  private commit?: CommitState;
  private discardReq?: { requestId: string; need: number; sel: Set<string> };   // B6 超限棄牌
  private guideCollapsed = false;

  private $ = (id: string) => document.getElementById(id)!;

  /** 防誤觸確認框;交戰中的抽牌/資源附趁隙攻擊提醒。確認後才送意圖。 */
  private confirmAction(title: string, message: string, action: IntentAction, payload?: Record<string, unknown>) {
    const engaged = (this.view?.you.engagedEnemyIds?.length ?? 0) > 0;
    const aoo = engaged && (action === "DRAW" || action === "GAIN_RESOURCE")
      ? "\n⚠️ 你正與敵人交戰:此行動會引發趁隙攻擊。" : "";
    void confirmDialog(message + aoo, { title, okText: "執行" })
      .then((ok) => { if (ok) this.onIntent?.(action, payload); });
  }

  constructor() {
    // 防誤觸:所有行動先彈確認框(dialog,非阻塞),確認才送意圖;行動點由伺服器在執行時才扣
    this.$("act-draw").onclick = () => this.confirmAction("🎴 抽牌", "花 1 行動抽 1 張牌?", "DRAW");
    this.$("act-resource").onclick = () => this.confirmAction("💰 資源", "花 1 行動獲得 1 資源?", "GAIN_RESOURCE");
    this.$("act-resign").onclick = () => {
      void confirmDialog("確定撤退?你將退出本劇本(成果保留;人數縮放不變)。", { title: "🏳️ 撤退", okText: "撤退" })
        .then((ok) => { if (ok) this.onIntent?.("RESIGN"); });
    };
    this.$("act-investigate").onclick = () =>
      this.confirmAction("🔍 調查", "發起調查檢定?(接著開投入面板;擲混沌袋後才結算行動)", "INVESTIGATE");
    this.$("act-endturn").onclick = () =>
      this.confirmAction("✋ 我打完了", "結束你本輪的行動?(全員完成即進行回合結算)", "END_TURN");
    this.$("act-endround").onclick = () => {
      void confirmDialog("強制結束全體回合?未用完的行動會消失(建議先在語音確認)。", { title: "⏭️ 全體結束", okText: "強制結束" })
        .then((ok) => { if (ok) this.onIntent?.("END_TURN", { force: true }); });
    };
    this.$("act-advance").onclick = () => this.confirmAction("📖 推進幕", "花費線索推進幕?", "ADVANCE_ACT");
    this.$("btn-save").onclick = () => this.onSave?.();
    this.$("commit-go").onclick = () => {
      if (this.discardReq) { this.submitDiscard(); return; }
      this.submitCommit([...(this.commit?.sel ?? [])]);
    };
    this.$("commit-none").onclick = () => this.submitCommit([]);
    // 回合進度小卡:收合/展開
    this.$("tg-toggle").onclick = () => {
      this.guideCollapsed = !this.guideCollapsed;
      (this.$("tg-body") as HTMLElement).hidden = this.guideCollapsed;
      this.$("tg-toggle").textContent = this.guideCollapsed ? "+" : "–";
    };
    // 事件紀錄:預設收合(約兩行),點擊展開/收合,不卡視野
    this.$("log").onclick = () => this.$("log").classList.toggle("open");
    // 手牌面板:收合/展開(釋放右下視野)
    this.$("hand-min").onclick = () => {
      const p = this.$("panel-hand");
      p.classList.toggle("min");
      this.$("hand-min").textContent = p.classList.contains("min") ? "+" : "–";
    };
  }

  /**
   * 檢定抽標記彈窗:大顆標記面翻轉出場 + 技能/難度 + 成功/失敗大字。
   * 自動淡出、點擊立即關閉;同訊息仍進 log 供回溯。標記面目前為預設樣式,之後可換圖。
   * 訊息格式(引擎 describeResult):「抽到 <標記>;技能 X ≥/< Y → 成功/失敗」
   */
  showTestResult(msg: string) {
    // 只對「抽到 …」的結算訊息彈窗;「技能檢定開始」等 SKILL_TEST 通知只進 log
    // (抽標記發生在玩家按「不投入/投入並檢定」之後,彈窗時機才正確)
    const m = msg.match(/^抽到 (.+?);技能 (\d+) (≥|<) (\d+) → (成功|失敗)$/);
    if (!m) return;
    document.getElementById("test-overlay")?.remove();
    const ok = msg.includes("→ 成功");
    const tokenDesc = m[1];
    // 標記面:數字修正(+1/-2…)直接當面;符號(💀 骷髏(-2) 等)取開頭符號、其餘當副標
    let face = tokenDesc, sub = "";
    const paren = tokenDesc.match(/^(.*?)[(（](.+)[)）]$/);   // 半形/全形括號皆可
    if (paren) { face = paren[1].trim(); sub = paren[2]; }
    const sp = face.indexOf(" ");
    if (sp > 0) { sub = face.slice(sp + 1) + (sub ? "(" + sub + ")" : ""); face = face.slice(0, sp); }

    const overlay = el("div");
    overlay.id = "test-overlay";
    const card = el("div", "test-card " + (ok ? "good" : "bad"));
    const img = tokenImageFor(tokenDesc);
    const chip = el("div", "token-chip", img ? undefined : face);
    if (img) {
      const pic = document.createElement("img");
      pic.className = "token-img";
      pic.src = `/tokens/${img}.svg`;
      pic.alt = tokenDesc;
      pic.onerror = () => { pic.remove(); chip.textContent = face; };   // 缺圖退回文字面
      chip.appendChild(pic);
    }
    card.appendChild(chip);
    if (sub) card.appendChild(el("div", "test-sub", sub));
    if (m) card.appendChild(el("div", "test-line", `技能 ${m[2]} ${m[3]} 難度 ${m[4]}`));
    card.appendChild(el("div", "test-verdict " + (ok ? "good" : "bad"), ok ? "✓ 成功" : "✗ 失敗"));
    overlay.appendChild(card);
    const close = () => { overlay.classList.add("out"); setTimeout(() => overlay.remove(), 300); };
    overlay.onclick = close;
    document.body.appendChild(overlay);
    setTimeout(() => { if (document.body.contains(overlay)) close(); }, 2800);
  }

  log(msg: string) {
    const box = this.$("log");
    box.appendChild(el("div", undefined, msg));
    box.scrollTop = box.scrollHeight;
  }

  // ------------------------------------------------------------------
  // 主渲染
  // ------------------------------------------------------------------
  render(view: GameStateView) {
    this.view = view;
    const you = view.you;
    const canAct = view.phase === "INVESTIGATION" && you.actionsRemaining > 0;

    // 頂部
    this.$("top-round").textContent = `第 ${view.round} 輪 · ${PHASE_ZH[view.phase] ?? view.phase}`;
    this.$("top-meter").textContent =
      `幕「${view.act.name}」線索 ${view.act.cluesSpent}/${view.act.threshold}` +
      `　·　密謀「${view.agenda.name}」${view.agenda.doom}/${view.agenda.threshold}`;

    // 自身
    this.$("self-name").textContent = (INV_NAME[you.investigatorId] ?? you.investigatorId) + " · 你";
    const skills = this.$("self-skills");
    skills.replaceChildren();
    (["WILLPOWER", "INTELLECT", "COMBAT", "AGILITY"] as SkillType[]).forEach((s) => {
      const wrap = el("span", "skill");
      wrap.appendChild(iconEl(s));
      const key = s.toLowerCase() as keyof typeof you.skills;
      wrap.appendChild(el("b", undefined, String(you.skills[key])));
      skills.appendChild(wrap);
    });
    this.$("self-vitals").textContent =
      `❤️ ${you.health - you.damage}/${you.health}　🧠 ${you.sanity - you.horror}/${you.sanity}`;
    this.$("self-econ").textContent =
      `💰 ${you.resources}　🔎 ${you.cluesHeld}　⚡ 行動 ${you.actionsRemaining}　🂠 牌堆 ${you.deckCount ?? 0}`;

    this.renderEnemies(view, canAct);
    this.renderHand(you.hand);
    this.renderPlayArea(you.playArea ?? []);
    this.updateActionButtons(view, canAct);
    this.renderTurnGuide(view, canAct);
  }

  /**
   * 回合進度小卡(像實體桌遊的輔助卡):四階段進度條 + 現在能做什麼。
   * 遊戲流程對新手偏複雜,這張卡隨時提示「第幾輪 / 哪階段 / 你還有幾個行動 / 可做的行動」。
   */
  private renderTurnGuide(view: GameStateView, canAct: boolean) {
    this.$("tg-round").textContent = `第 ${view.round} 輪`;

    // 四階段進度條(第 1 輪跳過神話)
    const PHASES: [string, string][] = [
      ["MYTHOS", "①神話"], ["INVESTIGATION", "②調查"], ["ENEMY", "③敵人"], ["UPKEEP", "④整備"],
    ];
    const curIdx = PHASES.findIndex((p) => p[0] === view.phase);
    const steps = this.$("tg-steps");
    steps.replaceChildren();
    PHASES.forEach(([ph, label], i) => {
      const s = el("div", "tg-step", label);
      if (ph === view.phase) s.classList.add("on");
      else if (curIdx >= 0 && i < curIdx) s.classList.add("done");
      steps.appendChild(s);
    });

    const now = this.$("tg-now");
    const hint = this.$("tg-hint");
    now.replaceChildren();
    hint.replaceChildren();

    const doneCount = (view.you.turnDone ? 1 : 0)
      + view.otherInvestigators.filter((o) => o.turnDone).length;
    const total = 1 + view.otherInvestigators.length;
    if (view.you.elimination) {
      now.append(view.you.elimination === "RESIGNED" ? "🏳️ 你已撤退 — 觀戰中" : "☠️ 你已被擊敗,退出本劇本 — 觀戰中");
      hint.append("等隊友完成本章;全員退場則本章以「未達成結局」收場。跨章回大廳後可再參戰。");
    } else if (view.phase === "INVESTIGATION") {
      if (view.you.turnDone) {
        now.append(`✅ 你已結束本輪 — 完成 ${doneCount}/${total}`);
        hint.append("等隊友按「✋我打完了」;全員完成自動結算敵人/神話。卡住時可「⏭️全體結束」強制。");
      } else if (canAct) {
        now.append("🎯 ");
        now.append(el("b", undefined, "調查階段 · 自由行動"));
        now.append(` — 你還有 ${view.you.actionsRemaining} 個行動(完成 ${doneCount}/${total})`);
        hint.append("可做:🚶移動 🔎調查 ⚔️戰鬥 💨閃避 🤝交戰 🃏打卡 · 打完按「✋我打完了」。");
      } else {
        now.append(`🎯 行動已用完 — 完成 ${doneCount}/${total}`);
        hint.append("按「✋我打完了」告訴隊友;全員完成才進敵人/神話階段(不強制順序)。");
      }
    } else {
      now.append(`⏳ ${PHASE_ZH[view.phase] ?? view.phase}結算中…`);
      hint.append("系統正在結算此階段(敵人移動/攻擊、神話抽卡等),稍候會回到調查階段。");
    }
  }

  private renderEnemies(view: GameStateView, canAct: boolean) {
    hideCardPreview();   // 重繪時收掉懸浮預覽
    const here = view.you.locationId;
    const mine = view.enemies.filter((e) => e.locationId === here);
    const box = this.$("here-enemies");
    box.replaceChildren();
    this.$("here-empty").hidden = mine.length > 0;

    for (const e of mine) {
      const row = el("div", "enemy");
      const name = el("div", "en-name", e.name);
      for (const kw of e.keywords) name.appendChild(el("span", "kw", kw));
      row.appendChild(name);

      const engaged = e.engagedWith === view.you.investigatorId;
      row.appendChild(el("div", "en-stat",
        `戰 ${e.fight}　閃 ${e.evade}　生命 ${e.health - e.damageOn}/${e.health}　攻 ${e.damage}傷/${e.horror}懼` +
        (engaged ? "　· 與你交戰" : e.engagedWith ? "　· 與隊友交戰" : "　· 未交戰") +
        (e.exhausted ? "　· 已耗竭" : "")));
      row.addEventListener("mouseenter", () => showEnemyPreview(e, row.getBoundingClientRect()));
      row.addEventListener("mouseleave", hideCardPreview);

      const btns = el("div", "en-btns");
      btns.appendChild(this.enemyBtn("⚔️ 戰鬥", canAct, () => this.onIntent?.("FIGHT", { enemyId: e.id })));
      btns.appendChild(this.enemyBtn("💨 閃避", canAct, () => this.onIntent?.("EVADE", { enemyId: e.id })));
      btns.appendChild(this.enemyBtn("🤝 交戰", canAct && !engaged, () => this.onIntent?.("ENGAGE", { enemyId: e.id })));
      row.appendChild(btns);
      box.appendChild(row);
    }
  }

  private enemyBtn(label: string, enabled: boolean, on: () => void): HTMLButtonElement {
    const b = el("button", undefined, label);
    b.disabled = !enabled;
    b.onclick = on;
    return b;
  }

  private renderHand(hand: HandCard[]) {
    hideCardPreview();   // 重繪時收掉懸浮預覽(mouseleave 可能來不及觸發)
    const box = this.$("hand-cards");
    box.replaceChildren();
    if (hand.length === 0) box.appendChild(el("span", "pip", "(無手牌)"));
    for (const c of hand) {
      const row = handRow(c);
      if (c.cardType === "asset" || c.cardType === "event") {
        row.classList.add("playable");
        row.title = `點擊打出 ${c.name}(費用 ${c.cost})`;
        row.onclick = () => {
          const aoo = (this.view?.you.engagedEnemyIds?.length ?? 0) > 0 ? "\n⚠️ 交戰中打牌會引發趁隙攻擊。" : "";
          void confirmDialog(`打出「${c.name}」(費用 $${c.cost})?${aoo}`, { title: "🃏 打出卡片", okText: "打出" })
            .then((ok) => { if (ok) this.onIntent?.("PLAY_CARD", { cardId: c.cardId }); });
        };
      }
      box.appendChild(row);
    }
  }

  /** 檯面已打出的支援:含簡述的列項(hover 大圖預覽;點擊啟動能力)。 */
  private renderPlayArea(playArea: HandCard[]) {
    hideCardPreview();
    const box = this.$("play-area");
    box.replaceChildren();
    (this.$("play-title") as HTMLElement).hidden = playArea.length === 0;
    for (const c of playArea) {
      const row = handRow(c);
      row.classList.add("prow");
      row.title = "點擊啟動這張卡的能力(⚡ 花 1 行動;沒有啟動能力伺服器會告訴你)";
      row.onclick = () => {
        void confirmDialog(`啟動「${c.name}」的能力?(⚡ 花 1 行動;若無啟動能力伺服器會提示)`,
            { title: "⚡ 啟動能力", okText: "啟動" })
          .then((ok) => { if (ok) this.onIntent?.("ACTIVATE", { cardId: c.cardId }); });   // C2 啟動
      };
      box.appendChild(row);
    }
  }

  private updateActionButtons(view: GameStateView, canAct: boolean) {
    const here: LocationView | undefined = view.locations.find((l) => l.id === view.you.locationId);
    const out = !!view.you.elimination;   // 已退場:所有行動關閉
    (this.$("act-draw") as HTMLButtonElement).disabled = !canAct || out;
    (this.$("act-resource") as HTMLButtonElement).disabled = !canAct || out;
    (this.$("act-resign") as HTMLButtonElement).disabled = out || view.phase !== "INVESTIGATION";
    (this.$("act-investigate") as HTMLButtonElement).disabled = !(canAct && (here?.clues ?? 0) > 0) || out;
    const endTurn = this.$("act-endturn") as HTMLButtonElement;
    endTurn.disabled = view.phase !== "INVESTIGATION" || view.you.turnDone || out;
    endTurn.textContent = view.you.turnDone ? "✅ 已結束(等隊友)" : "✋ 我打完了";
    (this.$("act-endround") as HTMLButtonElement).disabled = view.phase !== "INVESTIGATION";
    (this.$("act-advance") as HTMLButtonElement).disabled = view.phase !== "INVESTIGATION";
  }

  // ------------------------------------------------------------------
  // 技能檢定投入面板(多人同步屏障的客戶端 UI)
  // ------------------------------------------------------------------
  showCommit(requestId: string, opts: CommitCardsOptions) {
    this.discardReq = undefined;
    (this.$("commit-none") as HTMLButtonElement).hidden = false;
    (this.$("commit-go") as HTMLButtonElement).disabled = false;
    this.commit = { requestId, opts, sel: new Set() };
    this.$("commit-title").textContent =
      `技能檢定 · ${SKILL_ZH[opts.skill]}(${ICON[opts.skill].ch})`;
    this.$("commit-sub").textContent =
      `基礎 ${opts.base} vs 難度 ${opts.difficulty}　｜　你最多可投入 ` +
      `${opts.maxCommit >= 99 ? "不限(你是主檢定者)" : opts.maxCommit + " 張(協助隊友)"}`;

    const cards = this.$("commit-cards");
    cards.replaceChildren();
    opts.eligibleCards.forEach((c) => {
      const chip = cardChip(c);
      chip.title = `對此檢定 +${matchingIcons(c, opts.skill)}`;
      chip.onclick = () => this.toggleCommit(c.cardId, chip);
      cards.appendChild(chip);
    });
    this.$("commit-none-hint").textContent =
      opts.eligibleCards.length === 0 ? "(你沒有可投入的卡 → 直接送出「不投入」)" : "";
    this.updateCommitPreview();
    this.$("commit-backdrop").hidden = false;
  }

  private toggleCommit(cardId: string, chip: HTMLElement) {
    if (!this.commit) return;
    const { sel, opts } = this.commit;
    if (sel.has(cardId)) {
      sel.delete(cardId);
      chip.classList.remove("sel");
    } else {
      if (opts.maxCommit === 1) {
        sel.clear();
        [...this.$("commit-cards").children].forEach((c) => c.classList.remove("sel"));
      }
      if (sel.size < opts.maxCommit) {
        sel.add(cardId);
        chip.classList.add("sel");
      }
    }
    this.updateCommitPreview();
  }

  private updateCommitPreview() {
    if (!this.commit) return;
    const { sel, opts } = this.commit;
    let bonus = 0;
    for (const c of opts.eligibleCards) if (sel.has(c.cardId)) bonus += matchingIcons(c, opts.skill);
    const total = opts.base + bonus;
    const ok = total >= opts.difficulty;
    this.$("commit-preview").textContent =
      `投入 ${sel.size} 張 → 技能 ${total}(基礎 ${opts.base} + 投入 ${bonus}) vs 難度 ${opts.difficulty}` +
      `　${ok ? "✓ 目前可過(未計混沌標記)" : "⚠ 目前不足(尚需混沌標記或更多投入)"}`;
  }

  private submitCommit(cardIds: string[]) {
    if (!this.commit) return;
    const requestId = this.commit.requestId;
    this.commit = undefined;
    this.$("commit-backdrop").hidden = true;
    this.onCommit?.(requestId, cardIds);
    this.log(cardIds.length ? `已投入 ${cardIds.length} 張,等待其他玩家…` : "已送出「不投入」,等待其他玩家…");
  }

  // ------------------------------------------------------------------
  // B6:整備超限棄牌面板(CHOOSE_TARGET;沿用投入面板外框)
  // ------------------------------------------------------------------
  showDiscard(requestId: string, opts: ChooseTargetOptions) {
    this.discardReq = { requestId, need: opts.min, sel: new Set() };
    this.commit = undefined;
    this.$("commit-title").textContent = `🃏 手牌超過上限 — 請棄 ${opts.min} 張`;
    this.$("commit-sub").textContent = `整備後手牌上限 8:點選要棄掉的 ${opts.min} 張(棄完才能行動)。`;
    (this.$("commit-none") as HTMLButtonElement).hidden = true;   // 棄牌不可跳過
    const cards = this.$("commit-cards");
    cards.replaceChildren();
    for (const c of opts.candidates) {
      const chip = el("div", "card");
      chip.appendChild(el("span", "c-name", c.label));
      chip.onclick = () => {
        const { sel, need } = this.discardReq!;
        if (sel.has(c.id)) { sel.delete(c.id); chip.classList.remove("sel"); }
        else if (sel.size < need) { sel.add(c.id); chip.classList.add("sel"); }
        this.$("commit-preview").textContent = `已選 ${sel.size}/${need} 張`;
        (this.$("commit-go") as HTMLButtonElement).disabled = sel.size !== need;
      };
      cards.appendChild(chip);
    }
    this.$("commit-none-hint").textContent = "";
    this.$("commit-preview").textContent = `已選 0/${opts.min} 張`;
    (this.$("commit-go") as HTMLButtonElement).disabled = opts.min > 0;
    this.$("commit-backdrop").hidden = false;
  }

  private submitDiscard() {
    if (!this.discardReq) return;
    const { requestId, sel } = this.discardReq;
    this.discardReq = undefined;
    this.$("commit-backdrop").hidden = true;
    (this.$("commit-none") as HTMLButtonElement).hidden = false;   // 還原投入面板用法
    (this.$("commit-go") as HTMLButtonElement).disabled = false;
    this.onDiscard?.(requestId, [...sel]);
    this.log(`已棄 ${sel.size} 張,手牌回到上限內。`);
  }
}
