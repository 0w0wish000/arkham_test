#!/bin/bash
# 卡片資料載入(macOS —— 雙擊即可)。等同 ./setup-content.sh;更新用終端機跑 --refresh。
cd "$(dirname "$0")" || exit 1
chmod +x ./setup-content.sh 2>/dev/null
./setup-content.sh
echo ""
echo "（按 Enter 關閉）"
read -r _
