@echo off
rem ════════════════════════════════════════════════════════════════════
rem  Arkham 前端啟動器（Windows / 用瀏覽器玩）
rem  前置：Node 18+、網路（首次 npm install）。
rem
rem  用法：
rem    start-client.bat                # 連「本機」的伺服器（server 與 client 同一台）
rem    start-client.bat 192.168.1.50   # 連「別台」的伺服器（那台跑 start-server.bat）
rem ════════════════════════════════════════════════════════════════════
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0\client"

set "SERVER_HOST=%~1"
if "%SERVER_HOST%"=="" set "SERVER_HOST=localhost"
set "VITE_SERVER=ws://%SERVER_HOST%:8080"

rem 本機區網 IP（給區網其他人連本前端）
set "IP="
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "(Get-NetIPConfiguration ^| Where-Object {$_.IPv4DefaultGateway} ^| Select-Object -First 1).IPv4Address.IPAddress" 2^>NUL`) do set "IP=%%i"
if not defined IP set "IP=<這台IP>"

if not exist node_modules (
  echo 首次安裝前端相依（npm install）…
  call npm install
  if errorlevel 1 (
    echo(
    echo ✗ npm install 失敗。請確認已安裝 Node.js 18+（https://nodejs.org）。
    pause
    exit /b 1
  )
)

echo(
echo   前端啟動 —— 連向伺服器：%VITE_SERVER%
echo   本機開：       http://localhost:5173/?inv=joe_diamond
echo   區網其他人開： http://!IP!:5173/?inv=daniela
echo   參數：?inv=joe_diamond^|daniela（挑調查員） ?room=xxx（同房 = 同一場）
echo   按 Ctrl+C 可停止。
echo(

call npm run dev -- --host
