@echo off
REM Builds the OpenCam installer.
REM
REM Three steps, because the package contains two very different things:
REM   1. publish the WPF client, self-contained, so the installed app needs no
REM      separate .NET runtime
REM   2. build the native virtual camera media source with MSVC
REM   3. pack both, plus the wizard, the COM registration, the firewall
REM      exception and the Start-menu entry, into an MSI
REM
REM The result is OpenCamSetup.msi, next to this script once it finishes, and it
REM installs per machine: registering the camera under HKLM needs elevation.

setlocal
cd /d "%~dp0"

set "ROOT=%~dp0..\.."
set "PUBLISH=%ROOT%\dist\app"

echo [1/3] Publishing the client ...
dotnet publish "%ROOT%\windows\OpenCam.Client\OpenCam.Client.csproj" ^
    -c Release -r win-x64 --self-contained true ^
    -o "%PUBLISH%"
if errorlevel 1 (
    echo Client publish failed.
    exit /b 1
)

echo.
echo [2/3] Building the virtual camera ...
call "%ROOT%\windows\OpenCam.VirtualCam\build.bat"
if errorlevel 1 (
    echo Virtual camera build failed.
    exit /b 1
)

REM The media source ships beside the client: the frame server's service accounts
REM can read Program Files, and the registration points at this copy.
copy /Y "%ROOT%\windows\OpenCam.VirtualCam\build\OpenCamVirtualCam.dll" "%PUBLISH%\" >nul
if errorlevel 1 (
    echo Could not stage OpenCamVirtualCam.dll.
    exit /b 1
)

echo.
echo [3/3] Packing the installer ...
dotnet build "OpenCam.Setup.wixproj" -c Release -p:PublishDir="%PUBLISH%" -v q --nologo
if errorlevel 1 (
    echo Installer build failed.
    exit /b 1
)

echo.
echo Built bin\x64\Release\OpenCamSetup.msi
echo Check the package with: powershell -ExecutionPolicy Bypass -File verify-msi.ps1
exit /b 0
