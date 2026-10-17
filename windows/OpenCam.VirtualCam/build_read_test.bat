@echo off
REM Builds and runs the cross-language read test for the virtual camera.
REM
REM Start something publishing first, for example:
REM     ..\OpenCam.Probe\bin\Debug\net10.0-windows\opencam-probe.exe --publish-vcam 20
REM then run this script in a second window. It pulls a sample out of the media
REM source that reads the shared buffer and checks the pixels, so it only passes
REM when the C# writer and the C++ reader agree.

setlocal
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"

if not exist "%VCVARS%" (
    echo Could not find vcvars64.bat. Install the Visual Studio C++ build tools.
    exit /b 1
)

call "%VCVARS%" >nul
if errorlevel 1 exit /b 1

cd /d "%~dp0"
if not exist build mkdir build
if not exist build\test mkdir build\test

if not exist build\OpenCamVirtualCam.dll (
    call build.bat
    if errorlevel 1 exit /b 1
)

echo Building OpenCamVirtualCamReadTest.exe ...
cl /nologo /W4 /EHsc /O2 /std:c++17 ^
   OpenCamVirtualCamReadTest.cpp ^
   /Fo:build\test\ ^
   /Fe:build\OpenCamVirtualCamReadTest.exe ^
   /link /PDB:build\OpenCamVirtualCamReadTest.pdb ^
   mfplat.lib mfuuid.lib ole32.lib

if errorlevel 1 (
    echo Read test build failed.
    exit /b 1
)

echo.
build\OpenCamVirtualCamReadTest.exe %*
exit /b %errorlevel%
