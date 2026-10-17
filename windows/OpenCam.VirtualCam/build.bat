@echo off
REM Builds the OpenCam virtual camera media source.
REM Requires the Visual Studio 2022 C++ build tools and a Windows 10/11 SDK.

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

echo Building OpenCamVirtualCam.dll ...
cl /nologo /W4 /EHsc /O2 /DNDEBUG /std:c++17 /LD ^
   OpenCamVirtualCam.cpp OpenCamVirtualCameraHost.cpp ^
   /Fo:build\ ^
   /Fe:build\OpenCamVirtualCam.dll ^
   /link /DEF:OpenCamVirtualCam.def ^
   /PDB:build\OpenCamVirtualCam.pdb ^
   mfplat.lib mfsensorgroup.lib mfuuid.lib ole32.lib shlwapi.lib advapi32.lib

if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

echo.
echo Built build\OpenCamVirtualCam.dll
exit /b 0
