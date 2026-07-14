@echo off
rem ════════════════════════════════════════════════════════════════════
rem  Arkham 遊戲伺服器啟動器（Windows / 區域網路）
rem  任何一位玩家都能在自己電腦跑 —— 誰跑誰就是「主機」，與遊戲內的
rem  首席調查員（lead）無關。跑起來後把印出的網址給隊友即可。
rem
rem  前置：一套 JDK（理想 21；較舊版 Gradle 會自動抓 21）、網路（首次）。
rem  用法：直接雙擊本檔，或在終端機執行  start-server.bat
rem ════════════════════════════════════════════════════════════════════
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

rem 取得本機區網 IP（給隊友連）—— 用 PowerShell 抓「有預設閘道的網卡」，跨語系可靠
set "IP="
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "(Get-NetIPConfiguration ^| Where-Object {$_.IPv4DefaultGateway} ^| Select-Object -First 1).IPv4Address.IPAddress" 2^>NUL`) do set "IP=%%i"
if not defined IP set "IP=<你的區網IP>"

echo(
echo ════════════════════════════════════════════════════════════════════
echo   Arkham 遊戲伺服器（LAN） —— 主機：這台電腦（!IP!）
echo   伺服器監聽：  ws://!IP!:8080/ws/game
echo(
echo   隊友怎麼加入（擇一）：
echo    (1) 最簡單：在這台「再開一個視窗」執行  start-client.bat
echo        然後大家用瀏覽器開（每人挑不同調查員）：
echo           你   -^> http://!IP!:5173/?inv=joe_diamond
echo           隊友 -^> http://!IP!:5173/?inv=daniela
echo    (2) 各自跑：隊友在自己電腦執行  start-client.bat !IP!
echo        再開自己的 http://localhost:5173/?inv=daniela
echo(
echo   同一房間 = 同一場遊戲（預設房名 demo-session；可加 ?room=xxx）
echo ════════════════════════════════════════════════════════════════════
echo(
echo 啟動中（首次會下載 Gradle/JDK/相依，請耐心）…  按 Ctrl+C 可停止。
echo(

call gradlew.bat :server:bootRun --console=plain
