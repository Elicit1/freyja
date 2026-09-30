#!/usr/bin/env bash
# ================================================================
#           🎬 FREYJA AI DIGITAL STUDIO - 一键部署启动 (Linux/macOS)
# ================================================================

set -e

echo "================================================================"
echo "          🎬 FREYJA AI DIGITAL STUDIO - 一键部署启动"
echo "================================================================"
echo ""

# 1. 检查 Docker 运行环境
echo "[*] 正在检测 Docker 运行环境..."
if ! docker info > /dev/null 2>&1; then
    echo "[x] 错误: 未检测到 Docker 守护进程运行！"
    echo "    请确保已安装 Docker 并启动守护进程 (如 Docker Desktop 或 sudo systemctl start docker)。"
    exit 1
fi
echo "[√] Docker 环境检测正常。"
echo ""

# 2. 静默根据本地系统时区与区域进行嗅探判定
MAINLAND=false
TZ_VAL="$(date +%z 2>/dev/null || cat /etc/timezone 2>/dev/null || true)"
LOC_VAL="${LANG:-}${LC_ALL:-}"
if [[ "$TZ_VAL" =~ (Shanghai|Chongqing|Beijing|PRC|\+0800) ]] || [[ "$LOC_VAL" =~ (zh_CN|zh-CN) ]]; then
    MAINLAND=true
fi
export MAINLAND

# 3. 检查并初始化 .env 配置文件
if [ ! -f .env ]; then
    echo "[*] 未检测到 .env 配置文件，正在从 .env.example 自动复制生成..."
    cp .env.example .env
    echo "[√] 已自动创建 .env 配置文件。"
else
    echo "[√] 已存在 .env 配置文件。"
fi
echo ""

# 4. 一键构建并启动服务栈
echo "[*] 正在通过 Docker Compose 构建并启动核心服务栈..."
echo "    (首次启动需要拉取镜像并编译构建前端与后端，耗时约 2-5 分钟，请稍候...)"
echo ""

docker compose up -d --build

echo ""
echo "================================================================"
echo "  🎉 Freyja AI Digital Studio 服务集群已全部启动成功！"
echo "================================================================"
echo ""
echo "  - 创作工作台 (前端):  http://localhost"
echo "  - 后端 API 接口:      http://localhost:8080"
echo "  - FastAPI 调度网关:   http://localhost:8000/docs"
echo "  - MinIO 对象存储:     http://localhost:9001 (账号: minioadmin / 密码: minioadmin123)"
echo "  - MySQL 数据库:       localhost:3306        (账号: root / 密码: root)"
echo "  - Redis 缓存:         localhost:6379        (密码: 123456)"
echo ""
echo "如需停止服务，可执行 ./docker-stop.sh 或 \"docker compose stop\"。"
echo "================================================================"
