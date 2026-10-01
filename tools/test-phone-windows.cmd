@echo off
setlocal
cd /d "%~dp0.."
set "LOCKPIN_VS="
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "LOCKPIN_VS=%%i"
if not defined LOCKPIN_VS exit /b 1
call "%LOCKPIN_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
if not exist build mkdir build
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /Iwindows\common /Iwindows\broker windows\common\broker_protocol.cpp windows\common\canonical_transcript.cpp windows\common\phone_messages.cpp windows\common\pairing_crypto.cpp windows\common\phone_vault.cpp windows\broker\session_store.cpp windows\broker\rfcomm_server.cpp windows\tests\broker_core_test.cpp /Fobuild\ /Febuild\phone_broker_core_test.exe /link bcrypt.lib ws2_32.lib bthprops.lib crypt32.lib advapi32.lib shell32.lib ole32.lib
if errorlevel 1 exit /b 1
build\phone_broker_core_test.exe %*
exit /b %errorlevel%
