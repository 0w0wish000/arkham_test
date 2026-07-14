#!/bin/bash
# ════════════════════════════════════════════════════════════════════
#  Arkham 前端啟動器(macOS —— 雙擊即可,連本機伺服器)
#  等同 Windows 的 start-client.bat。
#  要連「別台主機」:在終端機跑  ./start-client.sh 192.168.1.50
# ════════════════════════════════════════════════════════════════════
cd "$(dirname "$0")" || exit 1
chmod +x ./start-client.sh 2>/dev/null
exec ./start-client.sh
