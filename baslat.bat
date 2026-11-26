@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo [EFOS-Ofis] Sanal ortam olusturuluyor...
    python -m venv .venv
    if errorlevel 1 (
        echo [HATA] Python bulunamadi. Lutfen python.org adresinden Python 3.10+ kurun ve PATH secenegini isaretleyin.
        pause
        exit /b 1
    )
)

.venv\Scripts\python.exe -c "import PyQt6, docx, openpyxl, pptx, pypdf, pypdfium2, PIL" >nul 2>&1
if errorlevel 1 (
    echo [EFOS-Ofis] Gerekli kutuphaneler yukleniyor...
    .venv\Scripts\python.exe -m pip install --upgrade pip
    .venv\Scripts\python.exe -m pip install -r requirements.txt
    if errorlevel 1 (
        echo [HATA] Kutuphaneler yuklenemedi. Internet baglantinizi kontrol edin.
        pause
        exit /b 1
    )
)

start "" ".venv\Scripts\pythonw.exe" main.py %*
endlocal
