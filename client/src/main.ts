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

  // 房間與調查員由網址參數決定(LAN 多人):?room=xxx&inv=joe_diamond|daniela
  const params = new URLSearchParams(location.search);
  const room = params.get("room") || "demo-session";
  const inv = params.get("inv") || "joe_diamond";
  try {
    await conn.connect(room, inv);
    log(`已連線:房間「${room}」· 調查員「${inv}」。等待伺服器狀態…`);
  } catch {
    log("⚠️ 無法連線。請先在 host 端啟動伺服器:./start-server.sh");
  }
}

main();
