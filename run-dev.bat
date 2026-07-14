@echo off
rem ════════════════════════════════════════════════════════════════════
rem  一鍵啟動（Windows）：伺服器 + 前端，各開一個視窗。
rem  雙擊本檔即可。關閉時各自關掉那兩個視窗（或在視窗內按 Ctrl+C）。
rem ════════════════════════════════════════════════════════════════════
chcp 65001 >nul
cd /d "%~dp0"

echo ▶ 啟動遊戲伺服器（新視窗）…
start "Arkham 伺服器" cmd /k start-server.bat

echo ▶ 等伺服器起來，再啟動前端（新視窗）…
timeout /t 5 >nul
start "Arkham 前端" cmd /k start-client.bat

echo(
echo 兩個視窗已開：一個是伺服器、一個是前端。
echo 瀏覽器開 http://localhost:5173/?inv=joe_diamond 即可開始。
echo （本視窗可關閉。）
