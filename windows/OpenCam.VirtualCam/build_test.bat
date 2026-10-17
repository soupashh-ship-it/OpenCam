@echo off
REM Builds and runs the virtual camera media source test.
REM The test loads the built DLL directly, so it needs no registration and no
REM administrator rights.

setlocal
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"

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

echo Building OpenCamVirtualCamTest.exe ...
cl /nologo /W4 /EHsc /O2 /std:c++17 ^
   OpenCamVirtualCamTest.cpp ^
   /Fo:build\test\ ^
   /Fe:build\OpenCamVirtualCamTest.exe ^
   /link /PDB:build\OpenCamVirtualCamTest.pdb ^
   mfplat.lib mfuuid.lib ole32.lib advapi32.lib

if errorlevel 1 (
    echo Test build failed.
    exit /b 1
)

echo.
build\OpenCamVirtualCamTest.exe
exit /b %errorlevel%
