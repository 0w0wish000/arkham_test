# src-tauri/ — Arkham 桌面殼(Tauri v2)

把前端包成桌面 App,並提供**讀玩家本機卡圖資料夾**的能力(瀏覽器沙箱做不到)。目前視窗載入 `prototype/deckbuilder.html`(選牌器)。

## 前置需求
- **Rust(`cargo`)** + 系統 webview(Tauri 需求;macOS 內建 WKWebView)。
- Tauri CLI:`cargo install tauri-cli --version "^2"`,或 `npm i -D @tauri-apps/cli`。

## 開發執行
```bash
# 從 repo 根目錄執行(Tauri CLI 會找同層的 ./src-tauri)
cargo tauri dev            # 或 npx @tauri-apps/cli dev
```
開一個桌面視窗載入選牌器。點 **「🖼 載入卡圖」→ 選你的卡圖資料夾**(檔名 `<code>.webp`)→ 卡磚換成真圖,沒有的維持 placeholder。

## 打包
```bash
npx @tauri-apps/cli icon path/to/icon.png   # 先產生各平台圖示
cargo tauri build
```

## Rust 指令(前端怎麼用)
- `pick_card_folder()` — 原生資料夾對話框 → 回傳路徑。
- `read_card_image(dir, code)` — 讀 `dir/<code>.{webp,png,jpg}` → 回 data URL(找不到回 null)。

前端以 `window.__TAURI__.core.invoke('read_card_image',{dir,code})` 呼叫(見 `prototype/deckbuilder.html` 的 `CARDIMG` 模組)。**無 Tauri(純瀏覽器)時自動退回**讀同層 `cardimg/`。

## 注意
- 卡圖為 FFG 版權 → **玩家自帶**,app 不散布(見 [docs/06 §12](../docs/06-card-data-and-images.md))。
- 此 scaffold **未經 `cargo` 編譯驗證**(此開發機無 Rust)。請以 `cargo tauri dev` 首次執行驗證/微調 crate 版本。
