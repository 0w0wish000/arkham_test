#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════
#  Arkham 前端啟動器(用瀏覽器玩)
#  前置:Node 18+、網路(首次 npm install)。
#
#  用法:
#    ./start-client.sh              # 連「本機」的伺服器(server 與 client 同一台)
#    ./start-client.sh 192.168.1.50 # 連「別台」的伺服器(那台跑 start-server.sh)
# ════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/client"

SERVER_HOST="${1:-localhost}"
export VITE_SERVER="ws://${SERVER_HOST}:8080"

lan_ip() {
  if command -v ipconfig >/dev/null 2>&1; then
    for i in en0 en1 en2; do ipconfig getifaddr "$i" 2>/dev/null && return; done
  fi
  hostname -I 2>/dev/null | awk '{print $1}'
}
IP="$(lan_ip)"; IP="${IP:-<這台IP>}"

[ -d node_modules ] || { echo "首次安裝前端相依…"; npm install; }

cat <<EOF

  🖥️  前端啟動 —— 連向伺服器:$VITE_SERVER
  本機開:      http://localhost:5173/?inv=joe_diamond
  區網其他人開: http://$IP:5173/?inv=daniela
  參數:?inv=joe_diamond|daniela(挑調查員) ?room=xxx(同房 = 同一場)
  Ctrl+C 可停止。

EOF

exec npm run dev -- --host
