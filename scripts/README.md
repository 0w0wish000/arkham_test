# scripts/ — 啟動與工具腳本

所有可執行腳本集中於此,**依用途分層**;每支腳本都有三個平台版本:

| 副檔名 | 平台 | 用法 |
|---|---|---|
| `.bat` | 🪟 Windows | 直接雙擊,或在終端機執行 |
| `.command` | 🍎 macOS | Finder 雙擊(會開 Terminal) |
| `.sh` | 🐧 macOS / Linux 終端機 | `./scripts/<層>/<名>.sh` |

所有腳本內部都會先切回 **repo 根目錄**,因此從任何位置執行(雙擊或終端機)都可以。

## start/ — 啟動使用(日常遊玩)

| 腳本 | 作用 |
|---|---|
| `run-dev.*` | **一鍵啟動**:伺服器 + 前端一起開(Windows/macOS 各開視窗;`.sh` 同終端機前景跑)。 |
| `start-server.*` | 只啟動 **Java 遊戲伺服器**(:8080)。誰跑誰就是 LAN 主機;會印出區網 IP 給隊友。首次會自動抓卡片資料。 |
| `start-client.*` | 只啟動**前端**(:5173)。可帶參數連別台主機:`start-client.sh 192.168.1.50`。 |

## setup/ — 資料準備(clone 後一次)

| 腳本 | 作用 |
|---|---|
| `setup-content.*` | 從 ArkhamDB 抓卡片資料 → `content/cards/generated/`(FFG 版權,不進 git;見 docs/06 §9)。冪等;`--refresh` 清快取重抓。忘了跑也沒關係:`start-server` 首次啟動會自動抓。 |

## test/ — 測試

| 腳本 | 作用 |
|---|---|
| `e2e.*` | **一鍵端到端測試**:起暫時伺服器(:18080)→ 協定 e2e(大廳/開打/存檔續玩)→ 前端建置 → 收尾。等同 `node e2e/run.mjs`;開關見 [docs/07](../docs/07-lan-setup.md#-端到端測試e2e邊改邊修用)。 |

> 其他非此層的腳本:`gradlew` / `gradlew.bat`(Gradle wrapper,依慣例留在根目錄)、`content/tools/*.py`(卡片資料管線,由 setup-content 呼叫)、`client/scripts/`(client 自用)。
