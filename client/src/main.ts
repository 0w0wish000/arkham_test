import { Connection } from "./net/Connection";
import { ClientStore } from "./state/ClientStore";
import { GameView } from "./render/GameView";

const log = (m: string) => {
  const el = document.getElementById("log")!;
  const d = document.createElement("div");
  d.textContent = m;
  el.appendChild(d);
  el.scrollTop = el.scrollHeight;
};

async function main() {
  const store = new ClientStore();
  const view = new GameView();
  await view.init(document.getElementById("app")!);

  // 經 Vite proxy 轉發到 Java 伺服器(ws://localhost:8080/ws/game)
  const wsUrl = `ws://${location.host}/ws/game`;
  const conn = new Connection(wsUrl, {
    onState: (v) => {
      store.set(v);
      view.render(v);
      log(`狀態更新:第 ${v.round} 輪 · ${v.phase} · 你在 ${v.you.locationId}`);
    },
    onEvent: (m) => log(m),
    onChoiceRequest: (req) => {
      // TODO: 依 req.kind 呈現決策 UI。COMMIT_CARDS = 投入判定畫面(見 prototype/)。
      log(`需要決策:${req.kind}(requestId=${req.requestId})`);
    },
    onError: (m) => log(`錯誤:${m}`),
  });

  // 點地點 → 送 MOVE 意圖
  view.onMove = (toLocationId) => conn.intent("MOVE", { toLocationId });

  try {
    await conn.connect("demo-session", "joe_diamond");
    log("已連線,送出 JOIN。等待伺服器狀態…");
  } catch {
    log("⚠️ 無法連線。請先啟動 Java 伺服器:./gradlew :server:bootRun");
  }
}

main();
