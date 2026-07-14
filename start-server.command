#!/bin/bash
# ════════════════════════════════════════════════════════════════════
#  Arkham 遊戲伺服器啟動器(macOS —— 雙擊即可)
#  Finder 直接雙擊本檔會開 Terminal 執行。等同 Windows 的 start-server.bat。
#  （在終端機也可直接跑 ./start-server.sh）
# ════════════════════════════════════════════════════════════════════
cd "$(dirname "$0")" || exit 1
chmod +x ./start-server.sh 2>/dev/null
exec ./start-server.sh
