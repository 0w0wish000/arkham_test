import { Application, Container, Graphics, Text } from "pixi.js";
import type { GameStateView, LocationView } from "../protocol";

/**
 * PixiJS 渲染層:把 GameStateView 畫成地圖,並把「點擊相連地點」轉成 MOVE 意圖。
 * 純呈現 —— 不做任何規則判定。真實版本會擴充敵人、token、技能檢定動畫等。
 */
export class GameView {
  app = new Application();
  private layer = new Container();
  onMove?: (toLocationId: string) => void;
  /** 可移動高亮框(呼吸動畫的目標;每次 render 重建)。 */
  private pulseFrames: Graphics[] = [];

  async init(parent: HTMLElement) {
    await this.app.init({ background: "#0d1117", resizeTo: parent, antialias: true });
    parent.appendChild(this.app.canvas);
    this.app.stage.addChild(this.layer);
    // 呼吸動畫:可移動格子的高亮框緩慢明滅,一眼看出「現在能走去哪」
    this.app.ticker.add(() => {
      if (this.pulseFrames.length === 0) return;
      const a = 0.5 + 0.4 * Math.sin(performance.now() / 320);
      for (const g of this.pulseFrames) g.alpha = a;
    });
  }

  render(view: GameStateView) {
    this.layer.removeChildren();
    this.pulseFrames = [];
    const W = this.app.renderer.width;
    const H = this.app.renderer.height;
    const pos = this.layout(view.locations, W, H);
    const here = view.you.locationId;
    const connectedToHere =
      view.locations.find((l) => l.id === here)?.connections ?? [];
    // 「現在真的能移動」才亮高亮(調查階段、還有行動、未退場);不能動時只保留可點(伺服器會說明原因)
    const canMoveNow = view.phase === "INVESTIGATION"
      && view.you.actionsRemaining > 0
      && !view.you.elimination;

    // 連線
    for (const loc of view.locations) {
      for (const c of loc.connections) {
        const a = pos.get(loc.id);
        const b = pos.get(c);
        if (!a || !b) continue;
        const g = new Graphics();
        g.moveTo(a.x, a.y).lineTo(b.x, b.y).stroke({ width: 2, color: 0x3a4b5c, alpha: 0.7 });
        this.layer.addChild(g);
      }
    }
    // 可走路徑亮線:你所在地 → 各相鄰格(疊在基礎連線上)
    if (canMoveNow) {
      const a = pos.get(here);
      for (const c of connectedToHere) {
        const b = pos.get(c);
        if (!a || !b) continue;
        const g = new Graphics();
        g.moveTo(a.x, a.y).lineTo(b.x, b.y).stroke({ width: 3, color: 0x5fbf6f, alpha: 0.55 });
        this.layer.addChild(g);
      }
    }

    // 地點卡
    for (const loc of view.locations) {
      const p = pos.get(loc.id)!;
      const card = new Container();
      card.x = p.x - 80;
      card.y = p.y - 44;

      const isHere = loc.id === here;
      const bg = new Graphics()
        .roundRect(0, 0, 160, 88, 10)
        .fill({ color: loc.revealed ? 0x1a2430 : 0x161d26 })
        .stroke({ width: 2, color: isHere ? 0xc9a24b : 0x3a4b5c });
      card.addChild(bg);

      card.addChild(new Text({
        text: loc.revealed ? loc.name : "未揭示地點",
        style: { fill: 0xe8e2d0, fontSize: 13 },
        x: 10, y: 8,
      }));
      if (loc.revealed) {
        card.addChild(new Text({
          text: `🌫️${loc.shroud}  🔎${loc.clues}${loc.enemyIds.length ? "  ⚔️" + loc.enemyIds.length : ""}`,
          style: { fill: 0x93a4b3, fontSize: 12 },
          x: 10, y: 34,
        }));
      }
      if (isHere) {
        card.addChild(new Text({ text: "🕵️", style: { fontSize: 20 }, x: 126, y: 54 }));
      }

      // 點擊相連地點 → 送 MOVE 意圖(伺服器仍會再驗證合法性)
      if (!isHere && connectedToHere.includes(loc.id)) {
        card.eventMode = "static";
        card.cursor = "pointer";
        card.on("pointertap", () => this.onMove?.(loc.id));

        if (canMoveNow) {
          // 可移動高亮框:綠色呼吸外框 + 外圈光暈 + 🚶 角標
          const glow = new Graphics()
            .roundRect(-5, -5, 170, 98, 13)
            .stroke({ width: 6, color: 0x5fbf6f, alpha: 0.18 })
            .roundRect(-2, -2, 164, 92, 11)
            .stroke({ width: 2.5, color: 0x5fbf6f });
          card.addChildAt(glow, 0);
          this.pulseFrames.push(glow);
          card.addChild(new Text({ text: "🚶", style: { fontSize: 15 }, x: 138, y: 6 }));
          // 滑過加深(呼吸之上直接拉滿)
          card.on("pointerover", () => { glow.alpha = 1; this.pulseFrames = this.pulseFrames.filter((g) => g !== glow); });
          card.on("pointerout", () => { this.pulseFrames.push(glow); });
        }
      }
      this.layer.addChild(card);
    }
  }

  /** 簡易環形佈局;真實版改用地點座標 / 連線圖排版。 */
  private layout(locs: LocationView[], W: number, H: number) {
    const m = new Map<string, { x: number; y: number }>();
    const cx = W / 2, cy = H / 2, r = Math.min(W, H) * 0.32;
    locs.forEach((l, i) => {
      const a = (i / locs.length) * Math.PI * 2 - Math.PI / 2;
      m.set(l.id, { x: cx + Math.cos(a) * r, y: cy + Math.sin(a) * r });
    });
    return m;
  }
}
