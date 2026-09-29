#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('status', 'promote-pair', 'promote-phone', 'promote-auth', 'promote-credentials',
        'disable-phone', 'enable-phone', 'disable-auth', 'enable-auth',
        'remove-phone', 'remove-auth', 'remove-both')]
    [string]$Action,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^S-1-5-21-(\d+-){3}\d+$')]
    [string]$UserSid
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Security

try {
    if (-not [Environment]::Is64BitProcess) { throw '64-bit PowerShell is required.' }
    $profileKey = "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\$UserSid"
    $profile = (Get-ItemProperty -LiteralPath $profileKey -Name ProfileImagePath).ProfileImagePath
    $profile = [Environment]::ExpandEnvironmentVariables($profile)
    if (-not [IO.Path]::IsPathRooted($profile)) { throw 'Invalid user profile path.' }

    $protectedDir = Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'WindowsLockPin'
    $localDir = Join-Path $profile 'AppData\Local\WindowsLockPin'
    $phoneName = "phone_$UserSid.dat"
    $authName = "$UserSid.bin"
    $credentialName = "$UserSid.cred"
    $protectedPhone = Join-Path $protectedDir $phoneName
    $protectedAuth = Join-Path $protectedDir $authName
    $protectedCredential = Join-Path $protectedDir $credentialName
    $localPhone = Join-Path $localDir $phoneName
    $localAuth = Join-Path $localDir $authName
    $localCredential = Join-Path $localDir $credentialName
    $phoneDisabled = Join-Path $protectedDir "phone_$UserSid.disabled"
    $authDisabled = Join-Path $protectedDir "$UserSid.auth-disabled"

    function Test-Record([string]$Path, [bool]$IsPhone) {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
        $item = Get-Item -LiteralPath $Path -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $item.Length -lt 600 -or $item.Length -gt 65536) { return $false }
        $plain = $null
        try {
            $cipher = [IO.File]::ReadAllBytes($Path)
            $plain = [System.Security.Cryptography.ProtectedData]::Unprotect(
                $cipher, $null, [System.Security.Cryptography.DataProtectionScope]::LocalMachine)
            if ($IsPhone) {
                if ($plain.Length -ne 696 -or [BitConverter]::ToUInt32($plain, 0) -ne 0x50484c50) { return $false }
            } else {
                if ($plain.Length -ne 3504 -or [BitConverter]::ToUInt32($plain, 0) -ne 0x314b504c) { return $false }
            }
            if ([BitConverter]::ToUInt32($plain, 4) -ne 1) { return $false }
            $sidInRecord = ([Text.Encoding]::Unicode.GetString($plain, 8, 368) -split [char]0, 2)[0]
            return $sidInRecord -ceq $UserSid
        } catch { return $false }
        finally { if ($null -ne $plain) { [Array]::Clear($plain, 0, $plain.Length) } }
    }

    function Publish-Record([string]$Source, [string]$Destination, [bool]$IsPhone) {
        if (-not (Test-Record $Source $IsPhone)) { throw 'Source record is missing or invalid.' }
        if (-not (Test-Path -LiteralPath $protectedDir -PathType Container)) {
            New-Item -ItemType Directory -Path $protectedDir -Force | Out-Null
        }
        $dirAcl = New-Object Security.AccessControl.DirectorySecurity
        $dirAcl.SetSecurityDescriptorSddlForm('O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)')
        Set-Acl -LiteralPath $protectedDir -AclObject $dirAcl
        $temp = "$Destination.pending"
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
        try {
            Copy-Item -LiteralPath $Source -Destination $temp -Force
            $fileAcl = New-Object Security.AccessControl.FileSecurity
            $fileAcl.SetSecurityDescriptorSddlForm('O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)')
            Set-Acl -LiteralPath $temp -AclObject $fileAcl
            if (Test-Path -LiteralPath $Destination) {
                # Windows PowerShell converts $null to an empty string for string parameters.
                # File.Replace requires a real null when no backup filename is requested.
                [IO.File]::Replace($temp, $Destination, [System.Management.Automation.Language.NullString]::Value)
            } else {
                [IO.File]::Move($temp, $Destination)
            }
            Set-Acl -LiteralPath $Destination -AclObject $fileAcl
            if (-not (Test-Record $Destination $IsPhone) -or
                (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash -ne
                (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash) {
                throw 'Published record verification failed.'
            }
        } finally {
            if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
        }
    }

    function Remove-Records([string[]]$Paths) {
        foreach ($path in $Paths) {
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
        }
    }

    function Resolve-RecordSource([string]$Local, [string]$Protected, [bool]$IsPhone, [string]$MissingMessage) {
        # Prefer the per-user staging copy; fall back to an already-published record so
        # promote-* also re-applies the strict ACL to vaults written before this fix.
        if (Test-Record $Local $IsPhone) { return $Local }
        if (Test-Record $Protected $IsPhone) { return $Protected }
        throw $MissingMessage
    }

    function Set-DisabledMarker([string]$Path) {
        if (-not (Test-Path -LiteralPath $protectedDir -PathType Container)) {
            throw 'Protected vault is missing.'
        }
        [IO.File]::WriteAllBytes($Path, [byte[]]@())
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetSecurityDescriptorSddlForm('O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)')
        Set-Acl -LiteralPath $Path -AclObject $acl
    }

    switch ($Action) {
        'status' {
            $auth = (Test-Record $protectedAuth $false) -or (Test-Record $localAuth $false)
            $phone = ((Test-Record $protectedPhone $true) -or (Test-Record $localPhone $true)) -and
                     ((Test-Record $protectedCredential $false) -or (Test-Record $protectedAuth $false) -or
                      (Test-Record $localCredential $false) -or (Test-Record $localAuth $false))
            $code = 0
            if ($auth) { $code = $code -bor 1 }
            if ($phone) { $code = $code -bor 2 }
            if ($auth -and -not (Test-Path -LiteralPath $authDisabled)) { $code = $code -bor 4 }
            if ($phone -and -not (Test-Path -LiteralPath $phoneDisabled)) { $code = $code -bor 8 }
            exit $code
        }
        'promote-pair' {
            $credentialSource = Resolve-RecordSource $localCredential $protectedCredential $false 'Phone credentials are missing.'
            Publish-Record $credentialSource $protectedCredential $false
            $phoneSource = Resolve-RecordSource $localPhone $protectedPhone $true 'Phone pairing is missing.'
            Publish-Record $phoneSource $protectedPhone $true
            Remove-Records @($phoneDisabled)
        }
        'promote-phone' {
            $phoneSource = Resolve-RecordSource $localPhone $protectedPhone $true 'Phone pairing is missing.'
            Publish-Record $phoneSource $protectedPhone $true
            Remove-Records @($phoneDisabled)
        }
        'promote-auth' {
            $authSource = Resolve-RecordSource $localAuth $protectedAuth $false 'Authenticator enrollment is missing.'
            Publish-Record $authSource $protectedAuth $false
            Remove-Records @($authDisabled)
        }
        'promote-credentials' {
            $credentialSource = Resolve-RecordSource $localCredential $protectedCredential $false 'Phone credentials are missing.'
            Publish-Record $credentialSource $protectedCredential $false
        }
        'disable-phone' {
            if (-not ((Test-Record $protectedPhone $true) -or (Test-Record $localPhone $true))) {
                throw 'Phone pairing is missing.'
            }
            Set-DisabledMarker $phoneDisabled
        }
        'enable-phone' { Remove-Records @($phoneDisabled) }
        'disable-auth' {
            if (-not ((Test-Record $protectedAuth $false) -or (Test-Record $localAuth $false))) {
                throw 'Authenticator enrollment is missing.'
            }
            Set-DisabledMarker $authDisabled
        }
        'enable-auth' { Remove-Records @($authDisabled) }
        'remove-phone' {
            Remove-Records @($protectedPhone, $localPhone, $protectedCredential, $localCredential,
                $phoneDisabled)
        }
        'remove-auth' {
            $phoneExists = (Test-Record $protectedPhone $true) -or (Test-Record $localPhone $true)
            if ($phoneExists -and -not (Test-Record $protectedCredential $false)) {
                $credentialSource = if (Test-Record $localCredential $false) { $localCredential }
                    elseif (Test-Record $protectedAuth $false) { $protectedAuth }
                    else { $localAuth }
                Publish-Record $credentialSource $protectedCredential $false
            }
            Remove-Records @($protectedAuth, $localAuth, $authDisabled)
        }
        'remove-both' {
            Remove-Records @($protectedPhone, $localPhone, $protectedCredential, $localCredential,
                $protectedAuth, $localAuth, $phoneDisabled, $authDisabled)
        }
    }
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 20
}
