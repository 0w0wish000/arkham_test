@echo off
rem Arkham LCG one-click launcher (portable bundle, Windows)
rem Starts the bundled game server (port 8080) and opens the browser.
chcp 65001 >nul
cd /d "%~dp0"

set "IP="
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "(Get-NetIPConfiguration ^| Where-Object {$_.IPv4DefaultGateway} ^| Select-Object -First 1).IPv4Address.IPAddress" 2^>NUL`) do set "IP=%%i"
if not defined IP set "IP=<LAN-IP>"

echo(
echo ============================================================
echo   Arkham LCG  --  server + client(單一埠 8080)
echo(
echo   你(主機):   http://localhost:8080/
echo   隊友(區網): http://%IP%:8080/
echo(
echo   防火牆跳出詢問時,勾「私人網路」並允許。
echo   結束遊戲:關閉本視窗或按 Ctrl+C。
echo ============================================================
echo(

rem server 就緒後自動開瀏覽器(背景等埠,最多約 80 秒)
start "" powershell -NoProfile -WindowStyle Hidden -Command "for($i=0;$i -lt 90;$i++){try{$c=New-Object Net.Sockets.TcpClient('127.0.0.1',8080);$c.Close();break}catch{Start-Sleep -Milliseconds 900}};Start-Process 'http://localhost:8080/'"

"%~dp0jre\bin\java.exe" -jar "%~dp0app\arkham-server.jar"
echo(
echo (server stopped)
pause >nul
