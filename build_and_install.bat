@echo off
setlocal

:: Clear scrollback so errors are easy to spot
powershell -Command "[System.Console]::Clear(); Write-Host ''" >nul 2>&1
:: Fallback cls for older terminals
cls

echo ========================================
echo   码记 MaJi - Build ^& Install
echo   %date% %time%
echo ========================================
echo.

cd /d "%~dp0"

echo [1/3] Building APK...
call .\gradlew.bat assembleRelease
if %ERRORLEVEL% neq 0 (
    echo.
    echo ========================================
    echo   BUILD FAILED - scroll up for errors
    echo ========================================
    exit /b 1
)
echo.

echo [2/3] Installing...
set APK=app\build\outputs\apk\release\app-release.apk
if not exist "%APK%" (
    echo APK not found: %APK%
    exit /b 1
)
adb install -r "%APK%"
if %ERRORLEVEL% neq 0 (
    echo Install FAILED!
    exit /b 1
)
echo.

echo [3/3] Launching...
adb shell am start -n com.zhaoyi.maji/.MainActivity
echo.
echo ========================================
echo   Done!
echo ========================================
