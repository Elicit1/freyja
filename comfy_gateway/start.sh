#!/usr/bin/env bash
# ==============================================================================
# Launch script for ComfyUI Universal OpenAI Gateway (Linux / macOS Bash)
# ==============================================================================

# 确保脚本切换到自身所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}" || exit 1

# 1. 尝试自动加载 .env 变量 (优先当前目录，次选根目录)
if [ -f "${SCRIPT_DIR}/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "${SCRIPT_DIR}/.env"
    set +a
elif [ -f "${SCRIPT_DIR}/../.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "${SCRIPT_DIR}/../.env"
    set +a
fi

# 2. 命令行入参优先级高于环境变量，环境变量优先级高于默认值
COMFY_HOST="${1:-}"
COMFY_PORT="${2:-}"
GATEWAY_PORT="${3:-${GATEWAY_PORT:-8000}}"

if [ -n "${COMFY_HOST}" ]; then
    PORT_TO_USE="${COMFY_PORT:-8188}"
    export COMFYUI_HTTP_URL="http://${COMFY_HOST}:${PORT_TO_USE}"
elif [ -z "${COMFYUI_HTTP_URL}" ]; then
    export COMFYUI_HTTP_URL="http://127.0.0.1:8188"
fi

if [ -z "${COMFYUI_WS_URL}" ]; then
    WS_BASE=$(echo "${COMFYUI_HTTP_URL}" | sed -e 's|^http://|ws://|' -e 's|^https://|wss://|')
    export COMFYUI_WS_URL="${WS_BASE}/ws"
fi

export GATEWAY_PORT="${GATEWAY_PORT}"
export PYTHONUNBUFFERED="1"
export PYTHONPATH="${SCRIPT_DIR}:${PYTHONPATH}"

echo "=========================================================="
echo " Starting ComfyUI Universal Multi-Workflow OpenAI Gateway "
echo " Port: ${GATEWAY_PORT} | Target ComfyUI: ${COMFYUI_HTTP_URL} "
echo "=========================================================="

exec python -m uvicorn main:app --app-dir "${SCRIPT_DIR}" --host 0.0.0.0 --port "${GATEWAY_PORT}" --reload
