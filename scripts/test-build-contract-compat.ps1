$ErrorActionPreference = 'Stop'

$contractScript = Join-Path $PSScriptRoot 'verify-build-contract.ps1'
$windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$output = & $windowsPowerShell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $contractScript 2>&1

if ($LASTEXITCODE -ne 0) {
    $output | ForEach-Object { Write-Output $_ }
    throw 'Build contract script must run under Windows PowerShell 5.1.'
}

$output | ForEach-Object { Write-Output $_ }
