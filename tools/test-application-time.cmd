@echo off
setlocal
cd /d "%~dp0.."
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "LIVING_TIME_VS=%%i"
if not defined LIVING_TIME_VS exit /b 1
call "%LIVING_TIME_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
if not exist build mkdir build
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /DUNICODE /D_UNICODE /D_WIN32_WINNT=0x0A00 /Iwindows\common /Iwindows\broker tests\application_time_test.cpp windows\common\pairing_crypto.cpp windows\common\broker_protocol.cpp windows\broker\rfcomm_server.cpp /Fobuild\ /Febuild\application_time_test.exe /link bcrypt.lib ws2_32.lib bthprops.lib ole32.lib uuid.lib advapi32.lib
if errorlevel 1 exit /b 1
build\application_time_test.exe %*
exit /b %errorlevel%
