# SIF Client

SIF Client 是独立的命令行客户端，通过 REST 访问 SIF Server。上传、提交、任务等待和图片下载均使用 Server 的 `/v1/**` 接口。Windows x64 发布包内含运行环境，解压后可以直接执行 `sif.exe`。

## Windows 发布包

解压 `sif-client-1.0.0-windows-x64.zip`，运行包内的 `install.cmd`。安装程序会将 `sif.exe` 复制到 `%LOCALAPPDATA%\Programs\SIF` 并加入当前用户的 PATH。重新打开 CMD 或 PowerShell 后执行以下命令。

```powershell
sif --help
sif configure --base-url http://127.0.0.1:8011
sif capabilities
```

也可以保留解压后的程序作为便携版，在 PowerShell 中执行以下命令。

```powershell
.\sif.exe --help
.\sif.exe configure --base-url http://127.0.0.1:8011
```

在 CMD 中可以直接执行当前目录的 `sif.exe`。发布包内附安装脚本与命令说明。

安装目录内的 `uninstall.cmd` 可以删除程序并移除用户 PATH 中对应的目录。默认保留保存的 Key，传入 `-RemoveConfig` 时清理配置文件。自定义配置可以通过 `-ConfigPath` 指定。

```powershell
& "$env:LOCALAPPDATA\Programs\SIF\uninstall.cmd"
# 同时删除默认配置中的 Key
& "$env:LOCALAPPDATA\Programs\SIF\uninstall.cmd" -RemoveConfig
```

## Python 源码安装

在本仓库根目录执行以下命令，也可以把整个 `sif_client` 目录复制到另一台机器安装。

```powershell
python -m pip install ./sif_client
sif configure --base-url http://127.0.0.1:8011
```

先在 Server 控制台的 API Keys 页面创建 Key，再在 `configure` 的隐藏输入提示中粘贴。配置一次后，每次调用会自动读取 `~/.sif/config.json` 中的服务地址和 Key。Windows 默认位置为 `%USERPROFILE%\.sif\config.json`。文件采用本地明文 JSON 存储，POSIX 权限为 `0600`，Windows 访问权限由用户目录 ACL 控制。配置采用临时文件与原子替换写入。

源码也可以直接运行。

```powershell
python sif_client/generate.py configure --base-url http://127.0.0.1:8011
python sif_client/generate.py capabilities
```

源码安装要求 Python 3.10+，运行时依赖标准库。安装后的客户端也支持 `python -m sif_client`。`sif configure --api-key-stdin` 从标准输入读取一行 Key，便于脚本配置。

Windows 发布包同样使用上述配置文件、Key 输入与恢复机制。

## 生图与恢复

```powershell
sif config
sif capabilities
sif readiness
sif t2i --tier fast --prompt "A red ceramic teapot beside a sunlit window" --width 2048 --height 1152 --no-expand --output teapot.png
sif i2i --tier fast --image teapot.png --prompt "Edit <Picture 1>. Change the teapot to cobalt blue. Preserve its shape and lighting." --width 2048 --height 1152 --no-expand --output edited.png
sif upload reference.png
sif queue
sif jobs
sif status JOB_UUID --output recovered.png
```

`i2i` 的 `--image` 可重复 1–16 次，支持本地文件与 Server 接受的 MinIO 地址。参数支持三档模型、MP/比例或精确尺寸、多张候选图、PNG/JPEG/WebP 与扩写配置。完整参数可查看 `sif t2i --help`、`sif i2i --help`。

提交后自动通过 `GET /v1/jobs/{id}/wait` 等待，再下载结果。进度和幂等键输出到 stderr，成功结果以 JSON 输出到 stdout，包含任务 ID、seed 和图片绝对路径。超时或断线后使用 `status` 恢复已有任务。提交响应丢失时，用同一 `--idempotency-key` 和相同参数恢复。`unknown` 任务需要服务端检查。

CLI 延续原有默认值 `standard`、4MP、扩写开启。示例显式选择 `fast`、2048×1152 和关闭扩写。`--wait-timeout` 默认 1800 秒，输出文件默认禁止覆盖，覆盖需传 `--overwrite`。

## 配置管理

```powershell
sif config
sif configure --base-url http://127.0.0.1:8011
sif --config ./private-client.json configure --base-url http://127.0.0.1:8011
sif --config ./private-client.json capabilities
sif logout
```

`config` 显示保存路径、地址与 Key 是否已保存。`logout` 删除配置中的 Key。服务端撤销可在控制台 API Keys 页面执行。环境变量中配置的 Key 在 `logout` 后仍会生效。

配置文件路径优先级为 `--config`、`SIF_CONFIG`、`COMFY_IMAGE_CONFIG`、`~/.sif/config.json`。显式指定的文件不存在时，普通调用会报错，`configure` 可以创建它。

请求地址优先级为全局 `--base-url`、`SIF_BASE_URL`、`COMFY_IMAGE_BASE_URL`、配置文件、`http://127.0.0.1:8011`。请求 Key 优先级为 `SIF_API_KEY`、`COMFY_IMAGE_API_KEY`、配置文件。`configure` 将输入的 Key 与指定地址写入所选文件。

`base_url` 填写 Server 基础地址。客户端会在其后拼接 `/v1/**`。连接地址应为最终地址，HTTP 重定向会报错。

## MCP 生图

SIF Server 同时发布 `/mcp`，Agent 可以在宿主应用中配置该端点与 Bearer Key，然后调用 `text_to_image` / `image_to_image` 和 `wait_for_job`。MCP 连接凭据由宿主应用保存。CLI 保存的配置用于 REST 请求。详细接入步骤见 [Server MCP 使用说明](../comfy_image_service/skills/sif-image-generation/references/mcp.md)。

## 验证

```powershell
python -m unittest discover -s sif_client/tests -v
```

## 构建 Windows 发布包

在 Windows x64 构建机安装 Python 3.10+，然后从仓库根目录执行以下命令。脚本会创建隔离构建环境并安装固定版本的 PyInstaller。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\sif_client\build-windows.ps1
```

产物位于 `sif_client/dist`，包含发布 ZIP、ZIP 的 SHA-256 文件和解压后的程序目录。ZIP 包含 `sif.exe`、安装与卸载脚本、`README.txt`。程序内含 Python 运行环境，目标机器可以直接运行。

使用构建产物执行回归测试。

```powershell
$env:SIF_EXE = (Resolve-Path .\sif_client\dist\sif-client-1.0.0-windows-x64\sif.exe).Path
python -m unittest discover -s sif_client/tests -v
```
