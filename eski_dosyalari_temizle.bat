@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem v2.2'ye gecisten kalan eski ve artik kullanilmayan dosyalari siler:
rem   android\               -> Kivy/Python Android surumu (yerini android_native aldi)
rem   android_native\build\  -> eski ara derleme dosyalari (imzasiz aligned.apk dahil)
rem   build\                 -> eski PyInstaller ara dosyalari
rem   __pycache__ klasorleri
rem   .github\workflows\build_*.yml -> eski iki is akisi (yerini efos-ofis.yml aldi)

echo Su klasorler silinecek:
if exist "android" echo   android\
if exist "android_native\build" echo   android_native\build\
if exist "build" echo   build\
echo   tum __pycache__ klasorleri
echo   .github\workflows\build_windows_exe.yml ve build_android_apk.yml
echo.
choice /c EH /m "Devam edilsin mi (E=Evet, H=Hayir)"
if errorlevel 2 goto :eof

if exist "android" rmdir /s /q "android"
if exist "android_native\build" rmdir /s /q "android_native\build"
if exist "build" rmdir /s /q "build"
if exist ".github\workflows\build_windows_exe.yml" del /q ".github\workflows\build_windows_exe.yml"
if exist ".github\workflows\build_android_apk.yml" del /q ".github\workflows\build_android_apk.yml"
for /d /r %%d in (__pycache__) do if exist "%%d" rmdir /s /q "%%d"
echo Temizlendi.
pause
endlocal
