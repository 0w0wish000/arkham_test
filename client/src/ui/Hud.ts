import type {
  GameStateView, EnemyView, HandCard, LocationView,
  SkillType, SkillIcon, IntentAction, CommitCardsOptions, ChooseTargetOptions,
} from "../protocol";

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

  constructor() {
    this.$("act-draw").onclick = () => this.onIntent?.("DRAW");
    this.$("act-resource").onclick = () => this.onIntent?.("GAIN_RESOURCE");
    this.$("act-resign").onclick = () => {
      if (confirm("確定撤退?你將退出本劇本(成果保留;人數縮放不變)。")) this.onIntent?.("RESIGN");
    };
    this.$("act-investigate").onclick = () => this.onIntent?.("INVESTIGATE");
    this.$("act-endturn").onclick = () => this.onIntent?.("END_TURN");   // 「我打完了」(屏障)
    this.$("act-endround").onclick = () => {
      if (confirm("強制結束全體回合?未用完的行動會消失(建議先在語音確認)。")) {
        this.onIntent?.("END_TURN", { force: true });
      }
    };
    this.$("act-advance").onclick = () => this.onIntent?.("ADVANCE_ACT");
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
        `戰 ${e.fight}　閃 ${e.evade}　生命 ${e.health - e.damageOn}/${e.health}` +
        (engaged ? "　· 與你交戰" : e.engagedWith ? "　· 與隊友交戰" : "　· 未交戰") +
        (e.exhausted ? "　· 已耗竭" : "")));

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
    const box = this.$("hand-cards");
    box.replaceChildren();
    if (hand.length === 0) box.appendChild(el("span", "pip", "(無手牌)"));
    for (const c of hand) {
      const chip = cardChip(c);
      if (c.cardType === "asset" || c.cardType === "event") {
        chip.classList.add("playable");
        chip.title = `打出 ${c.name}(費用 ${c.cost})`;
        chip.onclick = () => this.onIntent?.("PLAY_CARD", { cardId: c.cardId });
      } else if (c.cardType === "weakness") {
        chip.title = "弱點:留在手上(完整規則於後續實作)";
      } else {
        chip.title = "技能卡:於技能檢定的投入面板使用";
      }
      box.appendChild(chip);
    }
  }

  /** 檯面已打出的支援(沙盒:放大鏡 / 大砍刀 等)。 */
  private renderPlayArea(playArea: HandCard[]) {
    const box = this.$("play-area");
    box.replaceChildren();
    (this.$("play-title") as HTMLElement).hidden = playArea.length === 0;
    for (const c of playArea) box.appendChild(cardChip(c));
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
