@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem Kullanim:
rem   exe_yap.bat          -> dist\EFOS-Ofis.exe (tek dosya)
rem   exe_yap.bat klasor   -> dist\EFOS-Ofis\    (klasor surumu: cok daha hizli acilir, efos-mcp.exe icerir)

set "EFOS_ONEDIR="
if /i "%~1"=="klasor" set "EFOS_ONEDIR=1"

if not exist ".venv\Scripts\python.exe" (
    echo [EFOS-Ofis] Sanal ortam olusturuluyor...
    python -m venv .venv
    if errorlevel 1 (
        echo [HATA] Python bulunamadi. Lutfen Python 3.10+ kurulu oldugundan emin olun.
        pause
        exit /b 1
    )
)

echo [EFOS-Ofis] Kutuphaneler yukleniyor...
.venv\Scripts\python.exe -m pip install --upgrade pip
.venv\Scripts\python.exe -m pip install -r requirements.txt pyinstaller
if errorlevel 1 (
    echo [HATA] Bagimliliklar yuklenemedi.
    pause
    exit /b 1
)

echo [EFOS-Ofis] Testler calistiriliyor...
set "QT_QPA_PLATFORM=offscreen"
.venv\Scripts\python.exe -m unittest discover -s tests
if errorlevel 1 (
    echo [HATA] Testler basarisiz. Derleme iptal edildi.
    pause
    exit /b 1
)
set "QT_QPA_PLATFORM="

rem Yalnizca bu betigin kendi ciktilari temizlenir. dist\ klasorundeki diger dosyalar
rem (ornegin android_native\apk_derle.ps1 ile uretilen dist\EFOS-Ofis.apk) KORUNUR.
if exist "build" rmdir /s /q build
if exist "dist\EFOS-Ofis.exe" del /q "dist\EFOS-Ofis.exe"
if exist "dist\EFOS-Ofis" rmdir /s /q "dist\EFOS-Ofis"

echo [EFOS-Ofis] Derleniyor (EFOS-Ofis.spec)...
.venv\Scripts\python.exe -m PyInstaller --noconfirm --clean EFOS-Ofis.spec
if errorlevel 1 (
    echo [HATA] Derleme tamamlanamadi.
    pause
    exit /b 1
)

if defined EFOS_ONEDIR (
    if exist "dist\EFOS-Ofis\EFOS-Ofis.exe" (
        echo.
        echo [BASARILI] dist\EFOS-Ofis\ klasoru olusturuldu. Programi dist\EFOS-Ofis\EFOS-Ofis.exe ile acin.
        echo            Antigravity MCP icin ayni klasordeki efos-mcp.exe kullanilir.
    ) else (
        echo [HATA] Derleme ciktisi bulunamadi.
    )
) else (
    if exist "dist\EFOS-Ofis.exe" (
        echo.
        echo [BASARILI] dist\EFOS-Ofis.exe olusturuldu!
        echo            Antigravity MCP icin: EFOS-Ofis.exe --mcp
    ) else (
        echo [HATA] Derleme ciktisi bulunamadi.
    )
)
pause
endlocal
