@echo off
setlocal
cd /d "%~dp0.."
set "LOCKPIN_VS="
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "LOCKPIN_VS=%%i"
if not defined LOCKPIN_VS exit /b 1
call "%LOCKPIN_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
if not exist build mkdir build
set "WINSDK_VER=10.0.26100.0"
set "WINRT_INC=%ProgramFiles(x86)%\Windows Kits\10\Include\%WINSDK_VER%\cppwinrt"
set "LOCKPIN_FLAGS=/nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /guard:cf /Zi /DUNICODE /D_UNICODE /D_WIN32_WINNT=0x0A00 /Isrc /Iwindows\common /Iwindows\broker /Ithird_party\qrcodegen /I"%WINRT_INC%" /Fobuild\"
set "LOCKPIN_LIBS=bcrypt.lib crypt32.lib advapi32.lib shell32.lib ole32.lib shlwapi.lib secur32.lib credui.lib user32.lib gdi32.lib uuid.lib ws2_32.lib bthprops.lib WindowsApp.lib cfgmgr32.lib"
rc /nologo /fobuild\provider.res src\provider.rc
if errorlevel 1 exit /b 1
cl %LOCKPIN_FLAGS% /LD src\provider.cpp src\auth.cpp src\vault.cpp src\totp.cpp windows\common\broker_protocol.cpp windows\common\canonical_transcript.cpp windows\common\phone_messages.cpp windows\common\pairing_crypto.cpp windows\common\phone_vault.cpp windows\broker\session_store.cpp windows\broker\rfcomm_server.cpp windows\broker\ble_advertiser.cpp windows\broker\bluetooth_radio_watcher.cpp build\provider.res /Febuild\WindowsLockPin.dll /link /DEF:src\provider.def /DYNAMICBASE /NXCOMPAT /DEBUG /MAP:build\WindowsLockPin.map %LOCKPIN_LIBS%
if errorlevel 1 exit /b 1
rc /nologo /Isrc /fobuild\setup.res src\setup.rc
if errorlevel 1 exit /b 1
cl %LOCKPIN_FLAGS% src\setup.cpp src\provisioning.cpp src\qr_bitmap.cpp src\vault.cpp src\totp.cpp third_party\qrcodegen\qrcodegen.cpp build\setup.res /Febuild\WindowsLockPinSetup.exe /link /SUBSYSTEM:WINDOWS /MANIFEST:NO /DYNAMICBASE /NXCOMPAT %LOCKPIN_LIBS%
if errorlevel 1 exit /b 1
echo Build succeeded. No provider was installed or registered.
