param(
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\SIF'),
    [switch]$SkipPathUpdate
)

$ErrorActionPreference = 'Stop'
$installedFiles = @('sif.exe', 'uninstall.ps1', 'uninstall.cmd')
foreach ($name in $installedFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $name) -PathType Leaf)) {
        throw 'Extract all release files into a directory before running the installer.'
    }
}

$InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory)
New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
foreach ($name in $installedFiles) {
    $source = Join-Path $PSScriptRoot $name
    $destination = Join-Path $InstallDirectory $name
    if (-not [string]::Equals([IO.Path]::GetFullPath($source), $destination, [StringComparison]::OrdinalIgnoreCase)) {
        $temporary = Join-Path $InstallDirectory ('.sif-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        try {
            Copy-Item -LiteralPath $source -Destination $temporary
            Move-Item -LiteralPath $temporary -Destination $destination -Force
        } finally {
            if (Test-Path -LiteralPath $temporary) {
                Remove-Item -LiteralPath $temporary -Force
            }
        }
    }
}

if (-not $SkipPathUpdate) {
    [string]$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $entries = @($userPath -split ';' | ForEach-Object {
        [Environment]::ExpandEnvironmentVariables($_).Trim().TrimEnd('\')
    })
    if ($entries -notcontains $InstallDirectory.TrimEnd('\')) {
        $updatedPath = (@($userPath.TrimEnd(';'), $InstallDirectory) | Where-Object { $_ }) -join ';'
        [Environment]::SetEnvironmentVariable('Path', $updatedPath, 'User')
    }
}

Write-Output "Installed SIF Client to $InstallDirectory"
Write-Output 'Open a new CMD or PowerShell window, then run: sif configure --base-url http://127.0.0.1:8011'
