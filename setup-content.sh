#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════
#  卡片資料載入(macOS / Linux)—— 新 clone 後跑一次。
#
#  為什麼需要:卡片完整文字是 FFG 版權,不進 git(docs/06 §9);
#  repo 只留腳本與索引。此腳本從 ArkhamDB 抓一次、產出到本機:
#    content/cards/generated/*.json(核心+主線戰役,含調查員/劇本卡)
#
#  用法:
#    ./setup-content.sh            # 已有資料就略過(冪等)
#    ./setup-content.sh --refresh  # 清快取重抓(出新擴充 / 改調查員白名單後)
#  前置:python3、網路(首次;之後有快取可離線重建)。
# ════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")"

GEN="content/cards/generated"

if [ "${1:-}" = "--refresh" ]; then
  echo "♻️  --refresh:清除快取與舊產出,重新抓取…"
  rm -f content/tools/.cache/arkhamdb_all.json
  rm -rf "$GEN"
elif ls "$GEN"/*.json >/dev/null 2>&1; then
  echo "✓ 卡片資料已就緒($(ls "$GEN"/*.json | wc -l | tr -d ' ') 檔)。要更新請跑:./setup-content.sh --refresh"
  exit 0
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "✗ 找不到 python3。請先安裝 Python 3(https://www.python.org),再重跑本腳本。"
  exit 1
fi

python3 content/tools/build_cards.py
python3 content/tools/build_campaigns.py
echo
echo "✓ 完成。資料在 $GEN/(本機專用,不會進 git)。"
