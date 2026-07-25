# 12 · 打包與發行:讓第三方「下載一包就能玩」

> 目標:用戶下載**一個壓縮檔**(或安裝檔),解壓 → 雙擊 → server + client 一起起來,瀏覽器開 `http://localhost:8080` 就能玩;隊友開 `http://<主機IP>:8080` 加入。**不需要**預先安裝 JDK / Node / Python。

> ✅ **實作狀態:Phase 1(①–④)已落地。**
> `./gradlew distBundle` 一鍵出包(client 進 bootJar → jlink JRE → 啟動器 → zip);
> 啟動器與說明在 `packaging/`;CI(`.github/workflows/package.yml`)以
> windows/macos matrix 各出一包(手動觸發或推 `v*` tag 附上 Release)。
> 未做:⑤ 首跑抓卡 Java 化(發行包開箱先用內建卡目錄)、Phase 2(jpackage/Tauri)。

## 0. 為什麼這件事其實不難 —— 現有架構已經對打包友善

盤點現狀,三個關鍵事實讓打包路線非常短:

| 事實 | 出處 | 對打包的意義 |
|---|---|---|
| 前端已用**同源 WebSocket**:`ws://${location.host}/ws/game` | `client/src/main.ts:13` | 只要把建好的靜態前端交給 Java 伺服器同埠服務,**連線設定零改動**;dev 的 Vite proxy(`vite.config.ts`)只是開發期的橋 |
| 伺服器是 **Spring Boot 單體**(引擎內嵌) | `server/build.gradle` | `bootJar` 一鍵產出單一 fat jar,天生就是「一個檔案跑全部」 |
| 卡片資料**缺了也能玩**(退回內建卡目錄) | `CardDataLoader.java` | 發行包**不含** FFG 版權資料也能開箱即玩;完整卡庫可事後補抓 |

docs/07 結尾「正式版會更簡單」預言的就是這條路:**單埠 8080、主機跑一支程式、其他人開一個網址**。

## 1. 方案比較

| 方案 | 用戶體驗 | 工程量 | 包大小 | 附註 |
|---|---|---|---|---|
| **A. Portable Zip**(bootJar + 靜態前端 + 內嵌 JRE + 啟動器)| 解壓 → 雙擊 → 自動開瀏覽器 | **小(推薦起點)** | ~80–120 MB | 免安裝、免權限;Windows/macOS 各出一包 |
| **B. jpackage 安裝檔**(.exe/.msi/.dmg)| 雙擊安裝,有開始選單/圖示 | 中(A 完成後加一步)| 同上 | JDK 內建工具,吃 A 的產物;更「像個軟體」 |
| **C. Tauri 桌面 App + sidecar** | 原生視窗、系統匣、免瀏覽器 | 大 | 前端極小,但仍需捆 JRE 跑 server | `src-tauri/` 已有殼(目前只包選牌器原型);server 以 [sidecar](https://v2.tauri.app/develop/sidecar/) 隨附啟動;需 Rust 工具鏈與各平台建置 |
| **D. Docker(compose)** | `docker compose up` | 小 | 映像 ~300 MB | 只適合技術用戶 / 自架伺服器;**不建議**當玩家主要通路 |
| **E. GraalVM native-image** | 單一原生執行檔、秒開 | 很大 | 最小(~60 MB)| Spring Boot AOT 需處理反射/序列化(存檔序列化整個 GameState,風險高);**先不碰** |

**推薦路線:Phase 1 做 A(Portable Zip),跑順之後 Phase 2 視需求上 B(安裝檔)或 C(Tauri 桌面版)。** D 可以順手提供給自架族群。

## 2. Phase 1:Portable Zip 的組成與工程項目

### 2.1 發行包長相

```
arkham-<版本>-win64/
├─ Arkham遊戲.bat            ← 玩家唯一要碰的檔:啟動 server + 自動開瀏覽器
├─ jre/                      ← jlink 裁剪過的 Java 21 runtime(~50–70 MB)
├─ app/
│   └─ arkham-server.jar     ← bootJar(引擎 + 伺服器 + 靜態前端全在裡面)
├─ content/                  ← 卡片索引與腳本(❗不含 generated/,見 §4)
├─ saves/                    ← 自動存檔落地處(首跑建立)
└─ 說明.txt                  ← 開瀏覽器、隊友加入、防火牆三句話
```

macOS 版同構,啟動器換 `Arkham遊戲.command`。

### 2.2 工程項目(依序,互相獨立可分批做)

**① 伺服器服務靜態前端(半天)**
`npm run build` 產出 `client/dist/` → 進 jar 的 `static/`。Spring Boot 對 `classpath:/static/` 開箱即服務,零程式碼;只要 Gradle 接管建置順序:

```gradle
// server/build.gradle 追加
tasks.register('npmBuildClient', Exec) {
    workingDir '../client'
    // Windows 上 npm 是 npm.cmd
    commandLine System.getProperty('os.name').toLowerCase().contains('win') ? 'npm.cmd' : 'npm', 'run', 'build'
}
tasks.register('copyClientDist', Copy) {
    dependsOn 'npmBuildClient'
    from '../client/dist'
    into layout.buildDirectory.dir('resources/main/static')
}
tasks.named('bootJar') { dependsOn 'copyClientDist' }
```

之後 `./gradlew :server:bootJar` 產出的 jar,瀏覽器開 `http://localhost:8080/` 就是遊戲(WS 同埠,`WebSocketConfig` 已 `setAllowedOrigins("*")`,同源後更無問題)。**開發流程不變**(Vite + proxy 照舊),這是純加法。

**② jlink 裁 JRE(半天)**
發行包不能要求用戶裝 JDK。用 jlink 產出只含所需模組的 runtime:

```bash
jlink --add-modules java.base,java.logging,java.naming,java.desktop,java.management,java.security.jgss,java.instrument,jdk.unsupported,jdk.crypto.ec \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output jre
```

> 模組清單用 `jdeps --ignore-missing-deps --print-module-deps arkham-server.jar` 校準一次;Spring Boot fat jar 常見就是上面這組。

**③ 啟動器(半天)**
`Arkham遊戲.bat` 核心三行(照抄 `scripts/start/start-server.bat` 的印 IP 邏輯):

```bat
@echo off
cd /d "%~dp0"
start "" http://localhost:8080/
jre\bin\java -jar app\arkham-server.jar
```

再加:印區網 IP 給隊友(`http://<IP>:8080`)、8080 被占用時的提示。macOS 版 `.command` 同理。

**④ Gradle 一鍵組包(一天)**
root 加 `distZip` 類任務:`bootJar` → 複製 jre/ + 啟動器 + content 索引 + 說明 → `Zip`。之後發版就是 `./gradlew distBundle` 一個指令。

**⑤ 卡片資料首跑抓取 Java 化(1–2 天,可後補)** — 見 §4,發行品質的關鍵一步。

### 2.3 跨平台建置注意

- **jlink/jpackage 產物綁平台**:Windows 包要在 Windows 上建、macOS 包在 macOS 上建。建議上 **GitHub Actions matrix**(`windows-latest` / `macos-latest`)自動出兩包。
- macOS 未簽章的下載檔會被 Gatekeeper 攔:短期在說明檔教「右鍵 → 打開」;正式發行需 Apple Developer 簽章 + notarize。
- Windows 首跑防火牆彈窗:說明檔寫明勾「私人網路」(docs/07 已有現成文案)。

## 3. Phase 2:更像「遊戲」的兩條加值路

- **B. jpackage**:吃 Phase 1 產物,`jpackage --type msi …` 直接出安裝檔(內含 runtime、建捷徑)。工程量小,先做這個。
- **C. Tauri 桌面版**:`src-tauri/` 已有 v2 殼(目前 `frontendDist` 指向 `prototype/`,只包選牌器)。升級路線:`frontendDist` 改指 `client/dist`、把 `jre/ + jar` 宣告成 **sidecar** 隨 App 啟動/關閉、視窗直接載 `http://localhost:8080`。得到原生視窗與系統整合,但多了 Rust 工具鏈與簽章成本 —— 等 Zip 版被驗證受歡迎再投資。

## 4. ⚠️ 版權紅線:發行包「不能」帶什麼

| 內容 | 可否入包 | 依據 |
|---|---|---|
| 引擎 / 伺服器 / 前端程式碼 | ✅ | 自有資產 |
| `content/` 的索引、腳本、schema | ✅ | 自有資產 |
| `content/cards/generated/*.json`(完整卡片文字)| ❌ **不可散布** | FFG 版權,docs/06 §9(repo 也因此不進 git)|
| 卡圖 | ❌ 玩家自帶 | docs/06 §12 |

所以發行包的正確姿勢和 repo 一致:**包裡不帶資料,首跑時用戶自己從 ArkhamDB 抓**(ArkhamDB API 公開)。目前抓取靠 Python(`content/tools/build_cards.py`),但**不能要求玩家裝 Python** —— 這是 Phase 1 清單裡 ⑤ 的原因:

> **把「首跑抓卡」移植進 Java 伺服器**:啟動時若 `content/cards/generated/` 不存在,用 `java.net.http.HttpClient` 抓 ArkhamDB(邏輯照 `build_cards.py` 的白名單/欄位映射),寫到同目錄;失敗照舊退回內建卡目錄不擋玩。Python 管線保留給開發者(產 locale 模板等進階功能)。

過渡做法(⑤ 還沒做時):發行包照出,說明檔註明「首跑會用內建卡目錄;要完整卡庫需裝 Python 3 後雙擊 setup」。可玩,只是不完整。

## 5. 大小與體驗預估

| 項 | 大小 |
|---|---|
| bootJar(引擎+伺服器+前端 dist)| ~30–45 MB |
| jlink JRE | ~50–70 MB |
| 啟動器 + 說明 + content 索引 | <1 MB |
| **Zip 合計** | **~80–120 MB** |

首跑:解壓 → 雙擊 → 3–6 秒 Spring Boot 起來 → 瀏覽器自動開 → 進大廳。隊友:開 `http://<主機IP>:8080` 完事(**只剩一個埠**,比現在的 5173+8080 少一半防火牆問題)。

## 6. 建議執行順序(總結)

1. **①+③**:server 服務靜態前端 + 啟動器 → 在自己機器先體驗「單埠版」(這步同時改善 LAN 現況)
2. **②+④**:jlink + Gradle 組包 → 產出第一個可散布 Zip,找一位沒裝任何開發工具的朋友實測
3. **⑤**:首跑抓卡 Java 化 → 發行品質達標
4. CI matrix 出 Windows/macOS 雙包 → 掛上 Release
5. 視反響決定 Phase 2(jpackage 安裝檔 / Tauri 桌面版)
