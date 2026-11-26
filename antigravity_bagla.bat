@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem EFOS-Ofis MCP sunucusunu Google Antigravity'ye kaydeder.
rem Varsayilan izinli klasor: kullanici klasorunuz (%USERPROFILE%).
rem Farkli klasor icin:  antigravity_bagla.bat "D:\Belgeler"

if not exist ".venv\Scripts\python.exe" (
    echo [EFOS-Ofis] Once sanal ortam kuruluyor...
    python -m venv .venv
    .venv\Scripts\python.exe -m pip install -r requirements.txt
)

if "%~1"=="" (
    .venv\Scripts\python.exe mcp_server\register_antigravity.py
) else (
    .venv\Scripts\python.exe mcp_server\register_antigravity.py --allow "%~1"
)
echo.
echo Antigravity uygulamasini yeniden baslattiginizda "efos-ofis" MCP araclari aktif olacaktir.
pause
endlocal
