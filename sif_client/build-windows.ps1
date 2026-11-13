param([string]$Python = 'python')

$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$buildDirectory = Join-Path $projectDirectory 'build'
$environmentDirectory = Join-Path $buildDirectory 'venv'
$buildPython = Join-Path $environmentDirectory 'Scripts\python.exe'
$distributionDirectory = Join-Path $projectDirectory 'dist'

function Invoke-BuildPython {
    param([string[]]$Arguments)
    & $buildPython @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Build command failed with exit code $LASTEXITCODE."
    }
}

& $Python -c "import platform, struct, sys; sys.exit(0 if sys.platform == 'win32' and sys.version_info >= (3, 10) and struct.calcsize('P') == 8 and platform.machine().lower() in ('amd64', 'x86_64') else 1)"
if ($LASTEXITCODE -ne 0) {
    throw 'Build this release with a Windows x64 Python 3.10+ interpreter.'
}

if (-not (Test-Path -LiteralPath $buildPython)) {
    & $Python -m venv $environmentDirectory
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not create the isolated build environment.'
    }
}

Invoke-BuildPython -Arguments @('-m', 'pip', 'install', '--disable-pip-version-check', '-r',
    (Join-Path $projectDirectory 'requirements-build.txt'), $projectDirectory)
$version = & $buildPython -c "from importlib.metadata import version; print(version('sif-client'))"
if ($LASTEXITCODE -ne 0) {
    throw 'Could not read the Client version.'
}

$releaseName = "sif-client-$version-windows-x64"
$releaseDirectory = Join-Path $distributionDirectory $releaseName
$previousCache = $env:PYINSTALLER_CONFIG_DIR
$env:PYINSTALLER_CONFIG_DIR = Join-Path $buildDirectory 'pyinstaller-cache'
try {
    Invoke-BuildPython -Arguments @('-m', 'PyInstaller', '--noconfirm', '--onefile', '--console', '--noupx',
        '--name', 'sif', '--paths', $projectDirectory,
        '--distpath', $releaseDirectory, '--workpath', (Join-Path $buildDirectory 'pyinstaller'),
        '--specpath', $buildDirectory, (Join-Path $projectDirectory 'generate.py'))
} finally {
    $env:PYINSTALLER_CONFIG_DIR = $previousCache
}

$executable = Join-Path $releaseDirectory 'sif.exe'
& $executable --help
if ($LASTEXITCODE -ne 0) {
    throw 'The generated sif.exe failed its startup check.'
}

$releaseFiles = @($executable)
foreach ($name in @('install.ps1', 'install.cmd', 'uninstall.ps1', 'uninstall.cmd', 'README.txt')) {
    $destination = Join-Path $releaseDirectory $name
    Copy-Item -LiteralPath (Join-Path $projectDirectory "windows\$name") -Destination $destination -Force
    $releaseFiles += $destination
}

$archive = Join-Path $distributionDirectory "$releaseName.zip"
Compress-Archive -LiteralPath $releaseFiles -DestinationPath $archive -Force
$checksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
"$checksum  $releaseName.zip" | Set-Content -LiteralPath "$archive.sha256" -Encoding ascii
Write-Output "Release archive saved to $archive"
Write-Output "SHA256 $checksum"
