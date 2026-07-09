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

  async init(parent: HTMLElement) {
    await this.app.init({ background: "#0d1117", resizeTo: parent, antialias: true });
    parent.appendChild(this.app.canvas);
    this.app.stage.addChild(this.layer);
  }

  render(view: GameStateView) {
    this.layer.removeChildren();
    const W = this.app.renderer.width;
    const H = this.app.renderer.height;
    const pos = this.layout(view.locations, W, H);
    const here = view.you.locationId;
    const connectedToHere =
      view.locations.find((l) => l.id === here)?.connections ?? [];

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
