#!/usr/bin/env bash
# 一鍵啟動端到端開發環境:Java 遊戲伺服器(:8080)+ 前端(:5173)。
#
# 前置需求:
#   - JDK(建議 21;若只有較舊版,Gradle 會透過 foojay 自動下載 21 toolchain)
#   - Node.js 18+
#   - 網路(首次執行會下載 Gradle、JDK、相依套件)
#
# 用法:  ./run-dev.sh        （Ctrl+C 會一起關閉伺服器與前端）

set -euo pipefail
cd "$(dirname "$0")"

# 首次啟動:卡片資料不存在就自動抓一次(失敗不擋遊戲;詳見 setup-content.sh)
if ! ls content/cards/generated/*.json >/dev/null 2>&1; then
  echo "▶ 首次啟動:載入卡片資料(僅此一次)…"
  bash ./setup-content.sh || echo "⚠️ 卡片資料載入失敗,先略過;之後可手動跑 ./setup-content.sh"
fi

SERVER_PID=""
cleanup() {
  echo ""
  echo "🧹 關閉服務…"
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  # bootRun 會 fork 一個 JVM;收掉佔用 8080 的行程確保乾淨結束
  if command -v lsof >/dev/null 2>&1; then
    lsof -ti:8080 2>/dev/null | xargs kill 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "▶︎ 1/3  建置並啟動遊戲伺服器(背景)…"
./gradlew :server:bootRun > .server.log 2>&1 &
SERVER_PID=$!

echo "        等待 Spring Boot 就緒(首次會下載 Gradle/JDK/相依,請耐心)…"
READY=false
for _ in $(seq 1 240); do
  # 以 log 的 'Started' 訊號判斷(比 nc 埠掃描可靠)
  if grep -q "Started GameServerApplication\|Tomcat started on port" .server.log 2>/dev/null; then READY=true; break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "        ✗ 伺服器啟動失敗。最後 40 行 log:"; echo "----"; tail -40 .server.log; exit 1
  fi
  sleep 2
done
[ "$READY" = true ] && echo "        ✓ 伺服器已就緒" || { echo "        ✗ 逾時未就緒,見 .server.log"; exit 1; }

echo "▶︎ 2/3  安裝前端相依(僅首次)…"
cd client
[ -d node_modules ] || npm install

echo "▶︎ 3/3  啟動前端 → http://localhost:5173"
echo "        (在瀏覽器開啟後,點相連地點會送出 MOVE 意圖;Ctrl+C 一起關閉)"
npm run dev
