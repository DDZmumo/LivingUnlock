@echo off
setlocal
cd /d "%~dp0.."
set "LOCKPIN_VS="
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "LOCKPIN_VS=%%i"
if not defined LOCKPIN_VS exit /b 1
call "%LOCKPIN_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
if not exist build mkdir build
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /Isrc src\totp.cpp tests\totp_test.cpp /Fobuild\ /Febuild\totp_test.exe /link bcrypt.lib
if errorlevel 1 exit /b 1
build\totp_test.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /DUNICODE /D_UNICODE /Isrc /Ithird_party\qrcodegen tests\qr_test.cpp src\provisioning.cpp src\qr_bitmap.cpp third_party\qrcodegen\qrcodegen.cpp /Fobuild\ /Febuild\qr_test.exe /link user32.lib gdi32.lib
if errorlevel 1 exit /b 1
build\qr_test.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /Isrc tests\policy_test.cpp /Fobuild\ /Febuild\policy_test.exe
if errorlevel 1 exit /b 1
build\policy_test.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /Iwindows\broker tests\connection_window_test.cpp /Fobuild\ /Febuild\connection_window_test.exe
if errorlevel 1 exit /b 1
build\connection_window_test.exe
if errorlevel 1 exit /b 1
if not exist build\WindowsLockPin.dll call tools\build.cmd
if errorlevel 1 exit /b 1
set "WINSDK_VER=10.0.26100.0"
set "WINRT_INC=%ProgramFiles(x86)%\Windows Kits\10\Include\%WINSDK_VER%\cppwinrt"
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /DLOCKPIN_TESTING /DUNICODE /D_UNICODE /D_WIN32_WINNT=0x0A00 /Isrc /Iwindows\common /Iwindows\broker /I"%WINRT_INC%" tests\provider_test.cpp src\provider.cpp src\auth.cpp src\vault.cpp src\totp.cpp windows\common\broker_protocol.cpp windows\common\canonical_transcript.cpp windows\common\phone_messages.cpp windows\common\pairing_crypto.cpp windows\common\phone_vault.cpp windows\broker\session_store.cpp windows\broker\rfcomm_server.cpp windows\broker\ble_advertiser.cpp windows\broker\bluetooth_radio_watcher.cpp /Fobuild\ /Febuild\provider_test.exe /link bcrypt.lib crypt32.lib advapi32.lib shell32.lib ole32.lib shlwapi.lib secur32.lib credui.lib user32.lib gdi32.lib uuid.lib ws2_32.lib bthprops.lib WindowsApp.lib cfgmgr32.lib
if errorlevel 1 exit /b 1
build\provider_test.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /DLOCKPIN_VAULT_TESTING /DUNICODE /D_UNICODE /Isrc tests\vault_test.cpp src\vault.cpp src\totp.cpp /Fobuild\ /Febuild\vault_test.exe /link bcrypt.lib crypt32.lib advapi32.lib shell32.lib ole32.lib uuid.lib
if errorlevel 1 exit /b 1
build\vault_test.exe
exit /b %errorlevel%
