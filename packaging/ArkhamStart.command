#!/bin/bash
# Arkham LCG one-click launcher (portable bundle, macOS)
# 雙擊執行;若被 Gatekeeper 擋:對本檔右鍵 → 打開;或在終端機執行
#   xattr -dr com.apple.quarantine <解壓資料夾>
cd "$(dirname "$0")" || exit 1

IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)"
IP="${IP:-<LAN-IP>}"

cat <<EOF

============================================================
  Arkham LCG  --  server + client(單一埠 8080)

  你(主機):   http://localhost:8080/
  隊友(區網): http://$IP:8080/

  結束遊戲:關閉本視窗或按 Ctrl+C。
============================================================

EOF

# server 就緒後自動開瀏覽器(背景等埠,最多 90 秒)
( for _ in $(seq 90); do (echo >/dev/tcp/127.0.0.1/8080) 2>/dev/null && break; sleep 1; done
  open "http://localhost:8080/" ) &

exec ./jre/bin/java -jar app/arkham-server.jar
