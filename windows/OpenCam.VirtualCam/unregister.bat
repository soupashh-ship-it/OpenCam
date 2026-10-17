@echo off
REM Removes the OpenCam virtual camera registration. Run as Administrator.

setlocal
set "TARGET=%ProgramData%\OpenCam"

net session >nul 2>&1
if errorlevel 1 (
    echo This script must be run as Administrator.
    exit /b 1
)

if exist "%TARGET%\OpenCamVirtualCam.dll" (
    regsvr32 /s /u "%TARGET%\OpenCamVirtualCam.dll"
)

if exist "%TARGET%\OpenCamVirtualCam.dll" del /Q "%TARGET%\OpenCamVirtualCam.dll"
rmdir "%TARGET%" 2>nul

echo OpenCam virtual camera unregistered.
exit /b 0
