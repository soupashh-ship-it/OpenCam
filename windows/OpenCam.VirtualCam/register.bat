@echo off
REM Registers the OpenCam virtual camera media source. Run as Administrator.
REM
REM The media source is loaded by the Windows frame server services, which run as
REM Local Service and Local System. A DLL under a user profile is not readable by
REM those accounts, so it is copied to ProgramData first. This mirrors the
REM "Access Denied on Start" failure documented for Media Foundation virtual
REM cameras.

setlocal
set "TARGET=%ProgramData%\OpenCam"

net session >nul 2>&1
if errorlevel 1 (
    echo This script must be run as Administrator.
    echo Right-click register.bat and choose "Run as administrator".
    exit /b 1
)

if not exist "%~dp0build\OpenCamVirtualCam.dll" (
    echo build\OpenCamVirtualCam.dll not found. Run build.bat first.
    exit /b 1
)

echo Installing to %TARGET% ...
if not exist "%TARGET%" mkdir "%TARGET%"
copy /Y "%~dp0build\OpenCamVirtualCam.dll" "%TARGET%\OpenCamVirtualCam.dll" >nul
if errorlevel 1 exit /b 1

REM Everyone needs read and execute so the frame server services can load it.
icacls "%TARGET%\OpenCamVirtualCam.dll" /grant "*S-1-1-0:(RX)" >nul
if errorlevel 1 (
    echo Warning: could not set read permissions on the DLL.
)

echo Registering the media source ...
regsvr32 /s "%TARGET%\OpenCamVirtualCam.dll"
if errorlevel 1 (
    echo Registration failed.
    exit /b 1
)

echo.
echo OpenCam virtual camera registered.
echo Start OpenCam and turn on the virtual camera, then pick
echo "OpenCam" in Zoom, Teams, Discord or the Windows Camera app.
exit /b 0
