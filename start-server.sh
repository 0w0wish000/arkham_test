#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════
#  Arkham 遊戲伺服器啟動器(區域網路)
#  任何一位玩家都能在自己電腦跑 —— 誰跑誰就是「主機」,與遊戲內的
#  首席調查員(lead)無關。跑起來後把印出的網址給隊友即可。
#
#  前置:一套 JDK(理想 21;較舊版 Gradle 會自動抓 21)、網路(首次)。
#  用法:  ./start-server.sh
# ════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")"

# 首次啟動:卡片資料(FFG 版權,不進 git,docs/06 §9)不存在就自動抓一次。
# 失敗(無 python3 / 無網路)不擋遊戲 —— 目前對局用內建卡目錄;之後可手動跑 ./setup-content.sh
if ! ls content/cards/generated/*.json >/dev/null 2>&1; then
  echo "▶ 首次啟動:載入卡片資料(僅此一次)…"
  bash ./setup-content.sh || echo "⚠️ 卡片資料載入失敗,先略過(不影響目前遊玩);之後可手動跑 ./setup-content.sh"
fi

# 取得本機區網 IP(給隊友連)
lan_ip() {
  if command -v ipconfig >/dev/null 2>&1; then
    for i in en0 en1 en2; do ipconfig getifaddr "$i" 2>/dev/null && return; done
  fi
  if command -v hostname >/dev/null 2>&1 && hostname -I >/dev/null 2>&1; then
    hostname -I 2>/dev/null | awk '{print $1}' && return
  fi
  ifconfig 2>/dev/null | awk '/inet /{print $2}' | grep -v '^127\.' | head -1
}
IP="$(lan_ip)"; IP="${IP:-<你的區網IP>}"

cat <<EOF

════════════════════════════════════════════════════════════════════
  🎲 Arkham 遊戲伺服器(LAN) —— 主機:這台電腦($IP)
  伺服器監聽:  ws://$IP:8080/ws/game

  隊友怎麼加入(擇一):
   ①【最簡單】在這台「再開一個終端機」跑  ./start-client.sh
      然後大家用瀏覽器開(每人挑不同調查員):
         你   → http://$IP:5173/?inv=joe_diamond
         隊友 → http://$IP:5173/?inv=daniela
   ②【各自跑】隊友在自己電腦跑  ./start-client.sh $IP
      再開自己的 http://localhost:5173/?inv=daniela

  同一房間 = 同一場遊戲(預設房名 demo-session;可加 ?room=xxx)
════════════════════════════════════════════════════════════════════

啟動中(首次會下載 Gradle/JDK/相依,請耐心)…  Ctrl+C 可停止。
EOF

exec ./gradlew :server:bootRun --console=plain
