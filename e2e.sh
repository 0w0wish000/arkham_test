#!/usr/bin/env bash
# 一鍵端到端測試(macOS / Linux):啟動伺服器 → 協定 e2e → 前端建置 → 收尾。
# 用法:  ./e2e.sh
set -euo pipefail
cd "$(dirname "$0")"
exec node e2e/run.mjs "$@"
