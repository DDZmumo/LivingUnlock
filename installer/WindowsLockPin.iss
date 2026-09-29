#define MyAppName "LivingUnlock"
#define MyAppVersion "0.2.1"
#define MyAppPublisher "LivingUnlock"

[Setup]
AppId={{92ED55F4-7065-44C0-9903-C56F6CA59282}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion} 实验版
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\WindowsLockPin
DefaultGroupName=LivingUnlock
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableWelcomePage=no
UninstallDisplayIcon={app}\client\LivingUnlock.Windows.exe
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.22000
PrivilegesRequired=admin
WizardStyle=modern dynamic
Compression=lzma2/ultra64
SolidCompression=yes
OutputDir=..\dist
OutputBaseFilename=LivingUnlock-Setup-{#MyAppVersion}-x64
VersionInfoVersion={#MyAppVersion}.0
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription=LivingUnlock credential provider for Windows 11
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#MyAppVersion}
InfoBeforeFile=README_INSTALL.txt
SetupLogging=yes
CloseApplications=no
RestartApplications=no
ChangesAssociations=no
ChangesEnvironment=no
SetupMutex=WindowsLockPin-Setup-Mutex

[Files]
Source: "..\build\WindowsLockPin.dll"; DestDir: "{app}"; Flags: ignoreversion restartreplace uninsrestartdelete
Source: "..\build\WindowsLockPinSetup.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "README_INSTALL.txt"; DestDir: "{app}"; DestName: "安装说明.txt"; Flags: ignoreversion
Source: "..\build\desktop-publish\*"; DestDir: "{app}\client"; Excludes: "*.pdb"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\THIRD_PARTY_NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\third_party\qrcodegen\LICENSE.txt"; DestDir: "{app}\licenses\qrcodegen"; Flags: ignoreversion
Source: "..\third_party\qrcodegen\UPSTREAM.md"; DestDir: "{app}\licenses\qrcodegen"; Flags: ignoreversion
Source: "..\tools\Remove-Enrollment.ps1"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "..\tools\Manage-LivingUnlock.ps1"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "..\tools\Repair-VaultAcl.ps1"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "..\tools\VaultAclRepair.cs"; DestDir: "{app}\tools"; Flags: ignoreversion

[Registry]
Root: HKLM; Subkey: "SOFTWARE\Classes\CLSID\{{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}"; ValueType: string; ValueName: ""; ValueData: "LivingUnlock"; Flags: uninsdeletekey
Root: HKLM; Subkey: "SOFTWARE\Classes\CLSID\{{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}\InprocServer32"; ValueType: string; ValueName: ""; ValueData: "{app}\WindowsLockPin.dll"
Root: HKLM; Subkey: "SOFTWARE\Classes\CLSID\{{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}\InprocServer32"; ValueType: string; ValueName: "ThreadingModel"; ValueData: "Apartment"
Root: HKLM; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}"; ValueType: string; ValueName: ""; ValueData: "LivingUnlock"; Flags: uninsdeletekey

[Icons]
Name: "{group}\LivingUnlock"; Filename: "{app}\client\LivingUnlock.Windows.exe"
Name: "{group}\安装与恢复说明"; Filename: "{app}\安装说明.txt"
Name: "{group}\卸载 LivingUnlock"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\client\LivingUnlock.Windows.exe"; Description: "打开 LivingUnlock 控制台"; Flags: postinstall nowait skipifsilent

[Code]
function InitializeSetup(): Boolean;
var
  ExistingName: String;
begin
  Result := True;
  if RegQueryStringValue(HKLM64,
    'SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}',
    '', ExistingName) and (ExistingName <> 'WindowsLockPin Authenticator') and
    (ExistingName <> 'LivingUnlock') then
  begin
    MsgBox('相同 GUID 已由其他凭据提供程序占用。为避免破坏 Windows 登录，安装已停止。', mbCriticalError, MB_OK);
    Result := False;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
  PowerShellPath: String;
  CleanupScript: String;
begin
  if CurUninstallStep = usUninstall then
  begin
    if MsgBox('将注销 WindowsLockPin 登录组件并删除程序文件。' + #13#10 + #13#10 +
      '是否同时删除 ProgramData\WindowsLockPin 中所有用户的加密绑定数据？' + #13#10 +
      '选择“否”可保留数据供以后重新安装；原生 PIN 不受影响。',
      mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
    begin
      PowerShellPath := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
      CleanupScript := ExpandConstant('{app}\tools\Remove-Enrollment.ps1');
      if not Exec(PowerShellPath,
        '-NoProfile -ExecutionPolicy Bypass -File "' + CleanupScript + '"',
        '', SW_HIDE, ewWaitUntilTerminated, ResultCode) or (ResultCode <> 0) then
        MsgBox('绑定数据清理未完成。程序将继续卸载；请按照操作说明手动检查 ProgramData\WindowsLockPin。',
          mbError, MB_OK);
    end;
  end;
end;
