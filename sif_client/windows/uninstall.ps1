param(
    [string]$InstallDirectory = $PSScriptRoot,
    [switch]$RemoveConfig,
    [string]$ConfigPath = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.sif\config.json'),
    [switch]$SkipPathUpdate
)

$ErrorActionPreference = 'Stop'
$InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\')
$executable = Join-Path $InstallDirectory 'sif.exe'
if (Test-Path -LiteralPath $executable -PathType Leaf) {
    Remove-Item -LiteralPath $executable -Force
}

if (-not $SkipPathUpdate) {
    [string]$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $entries = @($userPath -split ';' | Where-Object {
        [Environment]::ExpandEnvironmentVariables($_).Trim().TrimEnd('\') -ine $InstallDirectory
    })
    [Environment]::SetEnvironmentVariable('Path', ($entries -join ';'), 'User')
}

if ($RemoveConfig -and (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    Remove-Item -LiteralPath ([IO.Path]::GetFullPath($ConfigPath)) -Force
    Write-Output "Removed configuration $ConfigPath"
}

foreach ($name in @('uninstall.ps1', 'uninstall.cmd')) {
    $path = Join-Path $InstallDirectory $name
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Remove-Item -LiteralPath $path -Force
    }
}
if ((Test-Path -LiteralPath $InstallDirectory -PathType Container) -and
    @(Get-ChildItem -LiteralPath $InstallDirectory -Force).Count -eq 0) {
    Remove-Item -LiteralPath $InstallDirectory -ErrorAction SilentlyContinue
}

Write-Output 'SIF Client uninstalled. Open a new terminal to refresh PATH.'
if (-not $RemoveConfig) {
    Write-Output 'Saved credentials are retained. Use -RemoveConfig to remove the selected configuration during uninstall.'
}
