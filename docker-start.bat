@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ================================================================
echo           🎬 FREYJA AI DIGITAL STUDIO - 一键部署启动
echo ================================================================
echo.

:: 1. 检查 Docker 是否安装且运行
echo [*] 正在检测 Docker 运行环境...
docker info >nul 2>&1
if %errorlevel% neq 0 (
    echo [x] 错误: 未检测到 Docker 守护进程运行！
    echo     请确保已安装并启动 Docker Desktop (或本地 Docker 服务)。
    echo.
    pause
    exit /b 1
)

echo [√] Docker 环境检测正常。
echo.

:: 2. 静默根据本地系统时区与区域进行嗅探判定
set "MAINLAND=false"
for /f "tokens=*" %%i in ('powershell -NoProfile -Command "if ([System.TimeZoneInfo]::Local.Id -match 'China|Beijing|Shanghai' -or [System.TimeZoneInfo]::Local.BaseUtcOffset.TotalHours -eq 8 -or (Get-Culture).Name -match 'zh-CN') { 'true' } else { 'false' }" 2^>nul') do (
    set "MAINLAND=%%i"
)

:: 3. 检查并初始化 .env 配置文件
if not exist .env (
    echo [*] 未检测到 .env 配置文件，正在从 .env.example 自动复制生成...
    copy .env.example .env >nul
    if %errorlevel% equ 0 (
        echo [√] 已自动创建 .env 配置文件。
    ) else (
        echo [x] 警告: 自动创建 .env 失败，请手动将 .env.example 复制为 .env。
    )
) else (
    echo [√] 已存在 .env 配置文件。
)
:: 升级旧版本地配置；自定义域名保持不变。
powershell -NoProfile -Command "$p='.env'; $s=[System.IO.File]::ReadAllText($p); $n=[regex]::Replace($s,'(?m)^MINIO_EXTERNAL_ENDPOINT=http://(?:localhost|127\.0\.0\.1):9000/?\r?$','MINIO_EXTERNAL_ENDPOINT=http://minio.localhost:9000'); if($n -ne $s){[System.IO.File]::WriteAllText($p,$n); Write-Host '[√] 已将旧版 MinIO localhost 地址升级为 minio.localhost。'}"
if %errorlevel% neq 0 exit /b 1
echo.

:: 4. 一键构建并启动服务栈
echo [*] 正在通过 Docker Compose 构建并启动核心服务栈...
echo     (首次启动需要拉取镜像并编译构建前端与后端，耗时约 2-5 分钟，请稍候...)
echo.

docker compose up -d --build

if %errorlevel% neq 0 (
    echo.
    echo [x] 启动过程中遇到错误，请检查上方控制台报错信息！
    echo     可使用 "docker compose logs -f" 查看容器详细日志。
    echo.
    pause
    exit /b %errorlevel%
)

:: 媒体 URL 会保存到分镜资产，部署时检查网关能否读取该地址。
docker compose exec -T comfy-gateway curl -fsS --max-time 10 "http://minio:9000/minio/health/live" >nul
if !errorlevel! neq 0 (
    echo [x] comfy-gateway 无法通过容器名称访问 MinIO (minio:9000)。
    exit /b 1
)
for /f "delims=" %%i in ('docker compose exec -T backend printenv MINIO_EXTERNAL_ENDPOINT') do set "MINIO_MEDIA_URL=%%i"
docker compose exec -T comfy-gateway python -c "import httpx, sys; httpx.get(sys.argv[1], timeout=10).raise_for_status()" "!MINIO_MEDIA_URL!/minio/health/live"
if !errorlevel! neq 0 (
    echo [x] MinIO 媒体地址无法从 comfy-gateway 容器访问: !MINIO_MEDIA_URL!
    echo     请将 MINIO_EXTERNAL_ENDPOINT 设置为浏览器和网关容器都能访问的地址。
    exit /b 1
)
echo [√] MinIO 媒体地址从网关容器访问正常: !MINIO_MEDIA_URL!

echo.
echo ================================================================
echo   🎉 Freyja AI Digital Studio 服务集群已全部启动成功！
echo ================================================================
echo.
echo   - 创作工作台 (前端):  http://localhost
echo   - 后端 API 接口:      http://localhost:8080
echo   - FastAPI 调度网关:   http://localhost:8000/docs
echo   - MinIO 对象存储:     http://localhost:9001 (账号: minioadmin / 密码: minioadmin123)
echo   - MySQL 数据库:       localhost:3306        (账号: root / 密码: root)
echo   - Redis 缓存:         localhost:6379        (密码: 123456)
echo.
echo ----------------------------------------------------------------
echo   正在自动为您在默认浏览器中打开工作台: http://localhost ...
echo ----------------------------------------------------------------
start http://localhost
echo.
echo 如需停止服务，可双击运行 docker-stop.bat 或执行 "docker compose stop"。
echo.
pause
