@echo off
chcp 65001 >nul

echo ================================================================
echo           🎬 FREYJA AI DIGITAL STUDIO - 停止服务
echo ================================================================
echo.
echo [*] 正在安全停止 Freyja 容器服务集群 (保留数据卷与资产)...
echo.

docker compose stop

if %errorlevel% equ 0 (
    echo.
    echo [√] 所有服务已成功停止！数据已安全持久化在 Docker 卷中。
    echo.
    echo 提示:
    echo   - 重新启动请双击: docker-start.bat
    echo   - 如需彻底清理容器与数据卷，请在命令行执行: docker compose down -v
) else (
    echo.
    echo [x] 停止服务时发生错误，请检查 Docker 状态。
)

echo.
pause
