param([string]$WslDistro = 'Ubuntu')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.env'))) {
    throw 'Create comfy_image_service/.env from .env.example and configure COMFY_URL first. SIF generates an initial administrator password on first boot.'
}
$linuxDirectory = (& wsl.exe -d $WslDistro --exec wslpath -u $PSScriptRoot).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the WSL path.' }
& wsl.exe -d $WslDistro --exec docker compose --project-directory $linuxDirectory -f "$linuxDirectory/compose.yaml" up -d --build
exit $LASTEXITCODE
