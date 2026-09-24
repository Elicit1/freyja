# Launch script for ComfyUI Universal OpenAI Gateway (Windows PowerShell)
param (
    [string]$ComfyHost = "",
    [int]$ComfyPort = 0,
    [int]$Port = 8000
)

# 确保工作目录自动切换到 comfy_gateway 根目录
Set-Location $PSScriptRoot
$env:PYTHONPATH = $PSScriptRoot

# 1. 尝试自动加载 .env 变量
$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    $envFile = Join-Path (Split-Path $PSScriptRoot -Parent) ".env"
}
if (Test-Path $envFile) {
    Get-Content $envFile -Encoding utf8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and (-not $line.StartsWith("#")) -and ($line -match '^([^=]+)=(.*)$')) {
            $k = $matches[1].Trim()
            $v = $matches[2].Trim().Trim('"').Trim("'")
            if (-not [System.Environment]::GetEnvironmentVariable($k)) {
                [System.Environment]::SetEnvironmentVariable($k, $v)
            }
        }
    }
}

# 2. 如果未指定命令行参数，优先采用环境变量或默认值
$ComfyHttp = ""
if ($ComfyHost -ne "") {
    $portToUse = 8188
    if ($ComfyPort -gt 0) {
        $portToUse = $ComfyPort
    }
    $ComfyHttp = "http://" + $ComfyHost + ":" + $portToUse
} elseif ($env:COMFYUI_HTTP_URL) {
    $ComfyHttp = $env:COMFYUI_HTTP_URL
} else {
    $ComfyHttp = "http://127.0.0.1:8188"
}

if (-not $env:COMFYUI_WS_URL) {
    $env:COMFYUI_WS_URL = $ComfyHttp.Replace("http://", "ws://").Replace("https://", "wss://") + "/ws"
}

$env:COMFYUI_HTTP_URL = $ComfyHttp
$env:GATEWAY_PORT = $Port
$env:PYTHONUNBUFFERED = "1"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Starting ComfyUI Universal Multi-Workflow OpenAI Gateway " -ForegroundColor Green
Write-Host " Port: $Port | Target ComfyUI: $ComfyHttp " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

python -m uvicorn main:app --app-dir $PSScriptRoot --host 0.0.0.0 --port $Port --reload
