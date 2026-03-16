@echo off
chcp 65001 >nul
setlocal

rem ==============================
rem Film LUT Batch Tool - Windows Launcher
rem ==============================

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
set "PYTHONUTF8=1"

echo =====================================
echo Film LUT Batch Tool
echo =====================================
echo.

rem Check Python
where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] python not found. Install Python 3.9+ and enable "Add to PATH".
    pause
    goto :eof
)

rem Check FFmpeg (optional)
where ffmpeg >nul 2>nul
if errorlevel 1 (
    echo [WARN] ffmpeg not found. Some features will be unavailable.
    echo Install from: https://ffmpeg.org/download.html and add it to PATH.
)

echo.
echo [STEP] Install or update dependencies...
python -m pip install -r "web_ui\requirements.txt"
if errorlevel 1 (
    echo.
    echo [ERROR] Dependency installation failed. Check Python and network.
    pause
    goto :eof
)

echo.
echo [STEP] Start service...
echo URL: http://127.0.0.1:8787
echo.

echo Starting, please wait...
timeout /t 2 /nobreak >nul

rem Open browser (non-blocking)
start "" "http://127.0.0.1:8787"

rem Run Flask app in current window for logs
python "web_ui\app.py"

echo.
echo Service stopped. Press any key to close.
pause >nul