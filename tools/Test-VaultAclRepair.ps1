#Requires -RunAsAdministrator
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -Path (Join-Path $PSScriptRoot 'VaultAclRepair.cs')
Add-Type -AssemblyName System.Security
$root = Join-Path (Split-Path -Parent $PSScriptRoot) ('artifacts\vault-acl-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$name = 'S-1-5-21-1-2-3-1001.cred'
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Rejected([string]$Path) {
    $rejected = $false
    try { [void][LivingUnlock.Maintenance.VaultAclRepair]::Repair($Path) } catch { $rejected = $true }
    Assert $rejected "Expected rejection: $Path"
}
function New-Case([string]$Name) {
    $path = Join-Path $root $Name
    New-Item -ItemType Directory -Path $path | Out-Null
    return $path
}

# Valid records are repaired; unrelated names keep their original ACL.
$normal = New-Case 'normal'
$record = Join-Path $normal $name
$unrelated = Join-Path $normal 'unrelated.bin'
[IO.File]::WriteAllText($record, 'synthetic data')
[IO.File]::WriteAllText($unrelated, 'synthetic data')
$originalUnrelated = (Get-Acl $unrelated).Sddl
Assert ([LivingUnlock.Maintenance.VaultAclRepair]::Repair($normal) -eq 1) 'Wrong repaired count'
$acl = Get-Acl $record
Assert $acl.AreAccessRulesProtected 'Record inheritance remains enabled'
foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
    Assert ($rule.IdentityReference.Value -in @('S-1-5-18','S-1-5-32-544')) 'Unexpected principal'
}
Assert ((Get-Acl $unrelated).Sddl -eq $originalUnrelated) 'Unrelated ACL changed'

$target = New-Case 'link-target'
$targetRecord = Join-Path $target $name
[IO.File]::WriteAllText($targetRecord, 'synthetic link target')
$targetAcl = (Get-Acl $target).Sddl
$targetFileAcl = (Get-Acl $targetRecord).Sddl
$junction = Join-Path $root 'junction'
New-Item -ItemType Junction -Path $junction -Target $target | Out-Null
Assert-Rejected $junction
Assert-Rejected (Join-Path $junction 'missing-child')
$symbols = New-Case 'symbolic-file'
New-Item -ItemType SymbolicLink -Path (Join-Path $symbols $name) -Target $targetRecord | Out-Null
Assert-Rejected $symbols
$hardlinks = New-Case 'hard-linked-file'
New-Item -ItemType HardLink -Path (Join-Path $hardlinks $name) -Target $targetRecord | Out-Null
Assert-Rejected $hardlinks
Assert ((Get-Acl $target).Sddl -eq $targetAcl) 'External directory ACL changed'
Assert ((Get-Acl $targetRecord).Sddl -eq $targetFileAcl) 'External file ACL changed'

# Exercise the real promotion functions using a synthetic DPAPI record in isolation.
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Manage-LivingUnlock.ps1'), [ref]$tokens, [ref]$errors)
Assert ($errors.Count -eq 0) 'Manager syntax errors'
foreach ($functionName in @('Test-Record','Publish-Record','Resolve-RecordSource')) {
    $function = $ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName}, $true)
    . ([scriptblock]::Create($function.Extent.Text))
}
$UserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$protectedDir = New-Case 'published'
$destination = Join-Path $protectedDir "$UserSid.cred"
$plain = New-Object byte[] 3504
[BitConverter]::GetBytes([uint32]0x314b504c).CopyTo($plain,0)
[BitConverter]::GetBytes([uint32]1).CopyTo($plain,4)
[Text.Encoding]::Unicode.GetBytes($UserSid).CopyTo($plain,8)
$encrypted = [Security.Cryptography.ProtectedData]::Protect($plain,$null,[Security.Cryptography.DataProtectionScope]::LocalMachine)
[IO.File]::WriteAllBytes($destination,$encrypted)
[Array]::Clear($plain,0,$plain.Length)
$before = (Get-FileHash $destination).Hash
$source = Resolve-RecordSource (Join-Path $root 'missing.cred') $destination $false 'missing'
Publish-Record $source $destination $false
Assert ((Get-FileHash $destination).Hash -eq $before) 'Republishing changed record bytes'
Assert ((Get-Acl $destination).AreAccessRulesProtected) 'Republishing did not protect ACL'
$staged = Join-Path $root 'staged.cred'
[IO.File]::WriteAllBytes($staged,$encrypted)
Assert ((Resolve-RecordSource $staged $destination $false 'missing') -eq $staged) 'Staged record not preferred'
Publish-Record $staged $destination $false
Assert ((Get-FileHash $destination).Hash -eq $before) 'Staged publish changed bytes'
Write-Output 'PASS: ACL repair, strict names, junction and file links rejected without target changes, existing record republish, local staging publish.'
Write-Output "Isolated synthetic fixtures retained at: $root"
