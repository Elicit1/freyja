# 🐳 Freyja 容器化部署指南 (Docker Compose)

本文档介绍如何使用 Docker Compose 一键启动 **Freyja（AI 短剧分镜管理与调度系统）** 核心服务栈，并无缝对接运行在 Windows、Linux 或云端 GPU 服务器上的 ComfyUI 算力端。

---

## 一、 部署架构与设计思想

AI 短剧系统的模型权重（Checkpoint / LoRA / ControlNet）通常体积庞大（数十至数百 GB），且强依赖不同宿主机的显卡驱动（NVIDIA CUDA / DirectML）。

为了让开源用户获得**开箱即用、轻量稳定**的体验，本项目采用 **“核心业务容器化 + 外部算力即插即用”** 的解耦架构：

- **Docker 容器集群**：一键编排启动业务核心，包括 **Web 前端 (Vue 3 / Nginx)**、**后端服务 (Spring Boot 4 / JDK 26 / FFmpeg)**、**MySQL 8.0 (自动初始化)**、**Redis 7.0**、**MinIO 对象存储** 以及 **FastAPI 生成网关**。用户不需要在宿主机折腾 JDK 26、Node.js、MySQL 等环境。
- **外部算力节点 (ComfyUI)**：创作者现有的本地电脑（Windows 常见秋叶整合包或官方 Portable 版）或云端 Linux GPU 服务器均可直接作为算力节点，通过网络无缝对接，免去在 Docker 中重新下载数十 GB 大模型和折腾显卡直通的痛苦。

```text
               ┌────────────────────────────────────────────────────────┐
               │              Freyja Docker Compose 容器集群            │
               │                                                        │
浏览器 ──────> │  ┌───────────────┐     /api/    ┌───────────────────┐  │
(http://localhost) │  │  Vue 3 前端   │ ────────────> │  Spring Boot 4    │  │
               │  │  (Nginx :80)  │              │  (JDK26+FFmpeg)   │  │
               │  └───────────────┘              └─┬──────┬────────┬─┘  │
               │                                   │      │        │    │
               │                                   ▼      ▼        ▼    │
               │                                MySQL   Redis    MinIO  │
               │                                                        │
               │                                   │ (生成调度)          │
               │                                   ▼                    │
               │                         ┌───────────────────┐          │
               │                         │   FastAPI 网关    │          │
               │                         │  (comfy_gateway)  │          │
               │                         └─────────┬─────────┘          │
               └───────────────────────────────────┼────────────────────┘
                                                   │
                                                   ▼ HTTP / WebSocket
                    ┌─────────────────────────────────────────────────┐
                    │          外部 ComfyUI 算力节点 (Windows / Linux) │
                    │   - Windows 本地创作者工作站 (RTX 4090/4080)     │
                    │   - 或 Linux 云端/局域网 GPU 服务器               │
                    └─────────────────────────────────────────────────┘
```

---

## 二、 5 分钟快速上手

### 1. 前置环境要求
- 已安装 **Docker** 与 **Docker Compose (v2.x+)**（Windows / Mac 可安装 [Docker Desktop](https://www.docker.com/products/docker-desktop/)，Linux 安装 `docker-ce` 与 `docker-compose-plugin`）。
- 确保系统空闲内存建议 $\ge 4\text{ GB}$。

### 2. 准备配置文件
克隆本仓库到本地后，在根目录复制环境变量模板：

```bash
cp .env.example .env
```

根据您的实际情况微调 `.env` 中的核心配置项：
- 若您的 ComfyUI 运行在**当前 Docker 宿主机电脑（Windows/Linux 本机）**：保持默认 `COMFYUI_HTTP_URL=http://host.docker.internal:8188` 即可；
- 若您的 ComfyUI 运行在**局域网其他主机或独立 GPU 云服务器**：修改为该机器的 IP 和端口，如 `COMFYUI_HTTP_URL=http://192.168.1.120:8188`。

### 3. 一键构建并启动
在项目根目录执行：

```bash
docker compose up -d --build
```

> **首次启动说明**：
> 1. Docker 会自动编译构建前端与后端镜像（后端内置 JDK 26 与 FFmpeg 工具链）；
> 2. MySQL 容器初次启动时，会自动挂载并执行 `docker/mysql/init/01_init.sql`，完成数据库、全量业务表结构、影视数据字典及内置系统配置的初始化；
> 3. 后端启动时会自动在 MinIO 中检查并初始化公开只读存储桶 `video-assets`。

### 4. 访问系统
服务启动完成后，即可在浏览器打开：

| 服务名称 | 默认访问入口 | 初始账密 / 说明 |
| :--- | :--- | :--- |
| **Freyja 创作工作台** | [http://localhost](http://localhost) | 前端主界面（剧本拆解/镜头组/分镜资产） |
| **后端 RESTful 接口** | [http://localhost:8080](http://localhost:8080) | Spring Boot 业务端点 |
| **FastAPI 调度网关** | [http://localhost:8000/docs](http://localhost:8000/docs) | OpenAPI 交互文档与工作流映射看板 |
| **MinIO 对象存储后台** | [http://localhost:9001](http://localhost:9001) | 用户名: `minioadmin`，密码: `minioadmin123` |
| **MySQL 数据库** | `localhost:3306` | 用户名: `root`，密码: `root`，库名: `freyja` |
| **Redis 缓存** | `localhost:6379` | 密码: `123456` |

---

## 三、 外部 ComfyUI 算力端配置说明 (Windows / Linux)

### 1. 硬件配置要求与模型准备
- **硬件配置建议**：GPU 显存建议 **$\ge 16\text{ GB}$**，宿主机物理内存建议 **$64\text{ GB}$**；
- **视频生成模型**：系统配套采用 ModelScope 发布的 **[MiniMax H3 混合高精度量化 (16G显存优化版)](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)**；
- **推理加速包**：建议配合下载 ModelScope 文件列表中的 **[ComfyUI-sol-attn-main.zip](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi/files)**（解压到 `ComfyUI/custom_nodes/ComfyUI-sol-attn`），专用于降低注意力计算显存开销与加速生视频；
- **工作流固定机制**：系统提示词组装与镜头注入与预置的 5 套工作流严格绑定，**不支持自动或随意接入第三方工作流**，开箱只使用系统内置工作流（新工作流接入逻辑与 AI 编写指引详见 [comfyUI_workflow/README.md](../comfyUI_workflow/README.md)）。

### 2. ComfyUI 监听与网络配置
为了让 Docker 容器顺利访问宿主机或局域网中的 ComfyUI，请确保 ComfyUI 允许外部连接与跨域访问：

#### (1) Windows 本地环境（秋叶整合包 / Portable 版）
- 启动脚本需添加 `--listen 0.0.0.0 --enable-cors-header` 参数（默认只监听 127.0.0.1 会导致 Docker 无法穿透访问）；
  - **秋叶整合包**：在启动器高级设置中勾选 **“允许局域网访问 / 监听 0.0.0.0”** 与 **“开启 CORS 跨域”**，端口保持 `8188`；
  - **官方便携版**：修改 `run_nvidia_gpu.bat`，在参数后加上 `--listen 0.0.0.0 --port 8188 --enable-cors-header`；
- Windows 防火墙若弹出提示，请选择 **“允许专用网络访问”**。

#### (2) Linux GPU 服务器
在启动 ComfyUI 时指定监听地址与跨域头：
```bash
python main.py --listen 0.0.0.0 --port 8188 --enable-cors-header
```

---

## 四、 常用运维管理命令

### 1. 查看容器运行状态与健康检查
```bash
docker compose ps
```

### 2. 查看服务实时日志
```bash
# 查看所有容器实时滚动日志
docker compose logs -f

# 仅查看后端 Spring Boot 日志
docker compose logs -f backend

# 仅查看 FastAPI 网关调度日志
docker compose logs -f comfy-gateway
```

### 3. 停止与重启服务
```bash
# 停止所有服务 (保留数据卷)
docker compose stop

# 启动已有服务
docker compose start

# 重启全部服务
docker compose restart
```

### 4. 数据持久化与彻底重置
所有数据库记录、媒体资产及日志均持久化在 Docker 命名卷中，重启或重新部署不会丢失数据：
- `freyja-mysql-data`: MySQL 数据库物理文件
- `freyja-redis-data`: Redis AOF/RDB 持久化文件
- `freyja-minio-data`: MinIO 对象存储图片/视频资产
- `freyja-backend-logs`: 后端运行日志

如需**彻底清空数据重新初始化**（⚠️ 请注意数据安全）：
```bash
docker compose down -v
```

---

## 五、 生产与公网部署建议

若您将本系统部署在公网云服务器（如腾讯云/阿里云/AWS）：
1. **修改默认凭据**：编辑 `.env` 文件，将 `MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、`MINIO_ROOT_PASSWORD` 以及 `FREYJA_CRYPTO_KEY` 修改为强密码；
2. **正确配置 MinIO 外部访问端点**：
   将 `.env` 中的 `MINIO_EXTERNAL_ENDPOINT=http://localhost:9000` 改为您的公网服务器 IP 或对应反向代理域名（如 `https://oss.yourdomain.com`），确保创作者浏览器能够正常加载分镜图片与视频流；
3. **防火墙与安全组**：仅对外开放前端 `80`（或 `443` HTTPS）端口与 MinIO 图片访问端口，MySQL/Redis 内部通信保持在 Docker 内网网段。
