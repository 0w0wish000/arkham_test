#!/bin/bash
# ════════════════════════════════════════════════════════════════════
#  Arkham 一鍵啟動(macOS —— 雙擊即可):伺服器 + 前端各開一個 Terminal 分頁。
#  等同 Windows 的 run-dev.bat。
# ════════════════════════════════════════════════════════════════════
cd "$(dirname "$0")" || exit 1
DIR="$(pwd)"
chmod +x ./start-server.sh ./start-client.sh 2>/dev/null

# 用 AppleScript 開兩個 Terminal 分頁分別跑 server / client
osascript >/dev/null 2>&1 <<EOF
tell application "Terminal"
  activate
  do script "cd " & quoted form of "$DIR" & " && ./start-server.sh"
  delay 4
  do script "cd " & quoted form of "$DIR" & " && ./start-client.sh"
end tell
EOF

echo ""
echo "已開兩個 Terminal:一個伺服器、一個前端。"
echo "瀏覽器開 http://localhost:5173/ 即可開始。（本視窗可關閉）"
