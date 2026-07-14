@echo off
rem 一鍵端到端測試(Windows):啟動伺服器 → 協定 e2e → 前端建置 → 收尾。
rem 用法:雙擊本檔,或在終端機執行  e2e.bat
chcp 65001 >nul
cd /d "%~dp0"
node e2e\run.mjs %*
echo(
echo （測試結束，離開碼 %errorlevel%。按任意鍵關閉。）
pause >nul
