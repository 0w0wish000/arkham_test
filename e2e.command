#!/bin/bash
# 一鍵端到端測試(macOS —— 雙擊即可)。等同 e2e.bat。
cd "$(dirname "$0")" || exit 1
node e2e/run.mjs "$@"
echo ""
echo "（測試結束,離開碼 $?。按 Enter 關閉。）"
read _
