#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$VaultDirectory = (Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'WindowsLockPin')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not [Environment]::Is64BitProcess) { throw '64-bit PowerShell is required.' }

$expected = [IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'WindowsLockPin'))
$resolved = [IO.Path]::GetFullPath($VaultDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
if (-not [string]::Equals($resolved, $expected, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Repair is restricted to the system WindowsLockPin vault.'
}

if (-not (Test-Path -LiteralPath $VaultDirectory -PathType Container)) {
    Write-Host "Vault directory does not exist yet: $VaultDirectory"
    Write-Host 'Nothing to repair. Save credentials / pair a phone first, then run promote-*.'
    exit 0
}

Add-Type -Path (Join-Path $PSScriptRoot 'VaultAclRepair.cs')
$count = [LivingUnlock.Maintenance.VaultAclRepair]::Repair($resolved)

Write-Host "Repaired $count record file(s) in $VaultDirectory."
Write-Host 'Re-run Manage-LivingUnlock.ps1 promote-* if you also want the per-user staging copies published.'
