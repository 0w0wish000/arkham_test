@echo off
rem ════════════════════════════════════════════════════════════════════
rem  卡片資料載入(Windows)—— 新 clone 後跑一次(雙擊即可)。
rem
rem  為什麼需要:卡片完整文字是 FFG 版權,不進 git(docs/06 §9);
rem  repo 只留腳本與索引。此腳本從 ArkhamDB 抓一次、產出到本機:
rem    content\cards\generated\*.json(核心+主線戰役,含調查員/劇本卡)
rem
rem  用法:
rem    setup-content.bat            (已有資料就略過)
rem    setup-content.bat --refresh  (清快取重抓:出新擴充/改白名單後)
rem  前置:Python 3、網路(首次)。
rem ════════════════════════════════════════════════════════════════════
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

if /i "%~1"=="--refresh" (
  echo ♻️  --refresh:清除快取與舊產出,重新抓取…
  del /q "content\tools\.cache\arkhamdb_all.json" 2>nul
  rmdir /s /q "content\cards\generated" 2>nul
) else (
  if exist "content\cards\generated\*.json" (
    echo ✓ 卡片資料已就緒。要更新請執行:setup-content.bat --refresh
    pause >nul
    exit /b 0
  )
)

set "PYCMD="
where py >nul 2>nul && set "PYCMD=py -3"
if not defined PYCMD ( where python >nul 2>nul && set "PYCMD=python" )
if not defined PYCMD (
  echo ✗ 找不到 Python。請先安裝 Python 3(https://www.python.org,勾選 Add to PATH),再重跑本腳本。
  pause
  exit /b 1
)

%PYCMD% content\tools\build_cards.py
echo(
echo ✓ 完成。資料在 content\cards\generated\(本機專用,不會進 git)。
echo （按任意鍵關閉）
pause >nul
