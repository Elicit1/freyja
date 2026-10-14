<h1 align="center">🎬 FREYJA AI DIGITAL STUDIO</h1>

<p align="center">
  <strong>基于 Spring Boot 4 + Vue 3 的影视级 AI 短剧工业化制作与 ComfyUI 调度平台</strong>
  <br />
  内部 Agent 协同 ｜ 外部 MCP 开放 ｜ 官方 MiniMax H3 视频模型 ｜ 二阶段人脸修复 ｜ 4/8-Step 极速加速
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/JDK-26-orange.svg" alt="JDK 26">
  <img src="https://img.shields.io/badge/Vue-3.5-42b883.svg" alt="Vue 3">
  <img src="https://img.shields.io/badge/MCP-Streamable%20HTTP-blueviolet.svg" alt="MCP">
  <img src="https://img.shields.io/badge/Agent-Internal%20Copilot-ff69b4.svg" alt="Internal Agent">
  <img src="https://img.shields.io/badge/Video%20Model-MiniMax%20H3%20Official-critical.svg" alt="MiniMax H3 Official">
  <img src="https://img.shields.io/badge/Acceleration-Turbo%204%2F8%20Step-yellow.svg" alt="Turbo 4/8 Step">
  <img src="https://img.shields.io/badge/Face%20Refine-Supported-success.svg" alt="Face Refinement">
  <img src="https://img.shields.io/badge/Docker-Compose%20v2-2496ed.svg" alt="Docker">
</p>

<br />

![FREYJA AI DIGITAL STUDIO](./image/freyja2.png)

<br />

---

## 📖 简介

**Freyja** 是一个专为 AI 短剧影视级工业化制作打造的开源创作管理、多模态资产沉淀与 ComfyUI 算力调度平台。系统贯穿**小说创作、剧本并行分段拆解、四层影视大纲管理、角色与多造型外观一致性维护、视听导演规划与双轨提示词衍生、分镜视频生成与抽卡版本追溯、以及超分补帧后期处理**的全流程生命周期。

系统深度融合大语言模型工程体系与前沿开源影视级生成模型，当前全面升级并推出 **五大核心能力**：

1. 🤖 **全能内部 Agent 创作中心 (Internal AI Agent)**：在 Freyja 工作台内直接与内置 Agent 对话交互，全自动完成需求澄清、小说创作、剧本分段拆解入库、角色与场景资产梳理及分镜制作准备。提供任务规划看板、实时步骤进度、断点续传、思考协议（DeepSeek / MiMo）适配与历史摘要压缩，支持 Codex Pet 专属虚拟化身陪伴创作。[配置与使用指引](docs/agent-api.md)。
2. 🔌 **统一外部 MCP 服务 (Model Context Protocol)**：开放标准 Streamable HTTP 协议端点，外部 AI 客户端（如 Claude Desktop、Cursor、Cline、Windsurf、OpenCode、Codex 等）可一键连接 Freyja，调用 72+ 个经过安全事务管控的业务工具，全权协同管理剧作大纲、资产与提示词；配套后台 `/system/ai-capabilities` 集中式能力发布与创作规则管理平台。[连接与 API 指南](docs/mcp-api.md)。
3. 🎥 **Comfy-Org 官方 MiniMax H3 影视级视频模型全面支持**：深度接入官方原生 INT8 ConvRot 视频 DiT 架构与 NVFP4 AWQ 32B 文本编码器，提供高保真 FL2VA（首尾帧生视频）与 Ref2VA（多参考图+配音驱动演播），自带影视级原生伴生音轨，画质更通透纯净、人物动效更自然、机位运镜更平滑。
4. ⚡ **4-Step / 8-Step 极速加速模型生态 (Turbo4 / Turbo8)**：全面接入 LightX2V 蒸馏加速 LoRA 矩阵，文生、首帧、首尾帧以及参考图视频渲染步数由传统 20+ 步骤降至 **4 步（Turbo4，秒级极速出片）** 或 **8 步（Turbo8，画质与速度黄金平衡）**，大幅缩短抽卡周期，显著节约 GPU 算力开销。
5. 🎭 **二阶段高保真智能人脸修复工作流 (Face Refinement)**：针对 AI 视频生成普遍面临的人物面部变形、五官闪烁失真等行业痛点，全新接入 ComfyUI-H3-FaceRefine 工作流扩展；基于 YOLOv8m 人脸检测定位与 InsightFace 身份特征锚定，对全视频人脸进行逐帧追踪、高清裁剪、Ref2VA 局部精准重绘与自适应融合贴回，且全系支持与 Turbo4/Turbo8 加速模型任意组合！

---

## 🌟 五大系统升级核心能力解析

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        FREYJA AI DIGITAL STUDIO                        │
├───────────────────────────────────┬────────────────────────────────────┤
│           🤖 内部 Agent 协同中心           │         🔌 统一外部 MCP 协议服务        │
│  - 沉浸式多轮创作会话 (小说/剧本/资产) │  - 72+ 项原子化 CRUD 与批量导入工具    │
│  - 结构化任务规划看板与实时步骤进度 │  - Streamable HTTP 协议 (Claude/Cursor) │
│  - DeepSeek / MiMo 思考协议与动态预算 │  - /system/ai-capabilities 能力规则中心 │
│  - 历史摘要持久化与 Codex Pet 虚拟形象  │  - 项目范围隔离与细粒度写入权限管控    │
├───────────────────────────────────┴────────────────────────────────────┤
│                         🎥 影视级生成与加速调度引擎                    │
│  ┌───────────────────────┬───────────────────────┬──────────────────┐  │
│  │   官方 MiniMax H3     │    ⚡ 4/8-Step 加速    │   🎭 二阶段人脸修复 │  │
│  │ • 官方 INT8 ConvRot   │ • LightX2V Turbo4 极速│ • YOLOv8m 逐帧跟踪│  │
│  │ • NVFP4 AWQ 32B 编码  │ • LightX2V Turbo8 均衡│ • InsightFace 锚定 │  │
│  │ • FL2VA / Ref2VA 双模 │ • 4 步出片，极速抽卡  │ • 局部重绘无缝贴回 │  │
│  └───────────────────────┴───────────────────────┴──────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

### 1. 🤖 内部 Agent：沉浸式剧作助手与任务执行中心
- **对话驱动剧作全流程**：内置助手支持结构化需求确认、小说创作、剧本拆解及入库、文字资产整理、分镜模式与参考素材规划、提示词准备和交付检查；缺少输入或待补图片时主动在聊天中澄清。
- **独立模型与策略配置**：在 `/system/agent` 为助手独立选择支持 Tool Calling 的 CHAT 模型，配置系统指令、挂载工具、Skills 知识库、记忆轮数、主模型与摘要模型独立预算、流式/完整回复以及执行超时策略。
- **思考协议深度适配**：支持 DeepSeek Chat 与 MiMo Chat 思考协议。DeepSeek 关闭思考时自动清洗不适用的推理参数，开启时规范采样；MiMo 支持显式思考开关，开启思考时清洗不生效的温度与 Top P。
- **长任务恢复与虚拟化身**：主模型和摘要模型预算解耦，会话达到上下文阈值时自动压缩历史并持久化检查点，任务支持实时取消与断点恢复；集成 Codex Pet 桌面虚拟化身陪伴创作。[查看详细说明](docs/agent-api.md)。

### 2. 🔌 统一外部 MCP 服务 (Model Context Protocol)
- **将 Freyja 接入你的专属 AI 工具链**：支持将 Freyja 作为标准 MCP Server 挂载至任意外部 AI 客户端（如 **Claude Desktop、Cursor、Cline、Windsurf、OpenCode、Codex** 等），外部 Agent 可直接读取短剧大纲、查询资产、编写分镜、上传参考图并写入双轨提示词。
- **72+ 个原子化业务工具**：覆盖短剧大纲（项目/剧集/场次/分镜）与多模态资产（角色/造型/场景/道具/关键帧）单条与批量 CRUD、事务级批量导入、资产参考图批量上传、以及已发布 Skills 目录与文件读取。
- **集中式能力与规则中心 (`/system/ai-capabilities`)**：所有内部 Agent 工具与外部 MCP 工具共享同一套 Java 业务能力层与创作规约；管理员可在线修改提示词约束与参数规则，版本发布即时生效，免除重启容器烦恼。
- **安全与权限审计 (`/system/mcp`)**：支持随机生成 24+ 字符 Bearer Token 鉴权、Origin 跨域安全校验、项目范围限制（限制 Agent 仅可操作指定短剧项目）、细粒度写入权限与单工具热开关。

### 3. 🎥 Comfy-Org 官方 MiniMax H3 影视级视频模型
- **原生架构支持**：全面接入 [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3) 官方 INT8 ConvRot 主模型，搭配 Qwen3-VL NVFP4 AWQ 32B 文本编码器与官方视频 VAE，告别第三方转制权重，画质细腻度与色彩通透度显著跃升。
- **首尾帧驱动 (FL2VA)**：精准依据起始首帧与结束尾帧生成高物理连贯性视频，并在镜头组 (ShotGroup) 内支持自动抽取上一镜末帧作为下一镜首帧，实现镜头无缝转场衔接。
- **多参考图驱动演播 (Ref2VA)**：支持角色立绘、特定造型与场景环境多图联合输入，配合音色台词驱动角色生动演播，表情与肢体动作自然逼真，自带高质量原生伴生音频。

### 4. ⚡ 4-Step / 8-Step 极速加速模型生态 (Turbo4 / Turbo8)
- **LightX2V 蒸馏加速**：集成 MiniMax H3 官方及 [LightX2V](https://huggingface.co/lightx2v/Minimax-h3-Turbo) 蒸馏 LoRA，彻底突破视频 DiT 生成速度瓶颈。
- **Turbo4 极速模式（4 步成片）**：仅需 4 步采样即可输出高质量视频，耗时缩减数倍，极适合创作者进行分镜构图探索、多版本镜头抽卡试镜。
- **Turbo8 均衡模式（8 步成片）**：在极短渲染时间内提供接近原版 20 步的微表情细腻感与毛发光影质感，适合终片批量生产交付。

### 5. 🎭 二阶段高保真智能人脸修复 (Face Refinement)
- **痛点根治**：彻底解决 AI 视频人物面部在复杂运动和远景下的五官变形、眼部坍塌和帧间闪烁问题。
- **两阶段无缝融合**：
  - **阶段一 (生成)**：由主视频模型基于提示词、首尾帧或参考图生成基础镜头动作；
  - **阶段二 (修复)**：集成 [ComfyUI-H3-FaceRefine](https://github.com/Carasibana/ComfyUI-H3-FaceRefine)，由 YOLOv8m 逐帧定位人脸边界框，InsightFace (`buffalo_l`) 提取身份特征，裁剪后由 Ref2VA 执行高精度人脸重绘，最后自适应融合贴回原视频。
- **全系兼容组合**：人脸修复工作流可与原 16GB HiFi、官方 Turbo4、官方 Turbo8 自由搭配，兼得极致出片速度与稳定人脸质感！

---

## 🔌 外部 MCP 快速连接指南

通过 Streamable HTTP 协议，将 Freyja 的短剧制作工业能力无缝连接至外部 AI 客户端：

### 1. 在 Freyja 开启 MCP 服务
1. 打开浏览器登录 Freyja 工作台，进入 **【系统管理 → MCP 服务 (`/system/mcp`)】**；
2. 生成或填写至少 24 位字符的随机访问令牌（Bearer Token）；
3. 设置允许访问的项目范围（可多选或全局允许），开启写入权限与所需工具，点击**开启 MCP 服务**。

### 2. 在外部 AI 客户端配置连接

#### 方案 A：TOML 格式客户端（如 Claude Desktop / 支持 TOML 的 Agent）
```toml
[mcp_servers.freyja]
url = "http://localhost/api/mcp"
bearer_token_env_var = "FREYJA_MCP_TOKEN"
tool_timeout_sec = 300
```
> 在系统环境变量中设置 `FREYJA_MCP_TOKEN` 为您在管理页配置的同一令牌。经前端反向代理时访问 `/api/mcp`，直接连接后端时访问 `http://localhost:8080/mcp`。

#### 方案 B：JSON 格式客户端（如 Cursor / Cline / Roo Code / Windsurf）
```json
{
  "mcpServers": {
    "freyja": {
      "url": "http://localhost/api/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_24_CHAR_MCP_TOKEN"
      },
      "timeout": 300
    }
  }
}
```

### 3. 开始创作协同
连接成功后，在客户端中直接向外部 Agent 下发任务指令，例如：
> “请先读取《绝世战神》项目的现有角色与场景资产，并调阅 cinematography 技能包；然后帮我把第 3 章小说拆解为剧集与场次，创建连续镜头，保留原文高潮对白并生成符合 H3 规范的双轨提示词，写入系统后读回核对。”

外部 Agent 将严格按照 Freyja 注册的创作规则与结构化格式完成数据治理与录入。

---

## 🎬 官方 MiniMax H3、加速与人脸修复模型工作流矩阵

系统通过 FastAPI 调度网关，在 [`comfy_gateway/nodes/machine_default.yaml`](comfy_gateway/nodes/machine_default.yaml) 中预置注册了 **9 个主流视频生成入口**，可直接在分镜工作台下拉选择：

| 网关模型编码 | UI 工作流文件 (`comfyUI_workflow/`) | 核心生成路线 | 采样步数 | 人脸修复 | 推荐场景与特点 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`minimax-h3-fl2va-turbo4`** | `video_minimax_h3_i2v_official_turbo4.json` | 官方 FL2VA 首尾帧 | **固定 4 步** | 否 | **秒级极速**首尾帧出片，768p 分辨率，快速抽卡试镜首选 |
| **`minimax-h3-fl2va-turbo8`** | `video_minimax_h3_i2v_official_turbo8.json` | 官方 FL2VA 首尾帧 | **固定 8 步** | 否 | 官方首尾帧均衡版，画质细腻度高，544p/768p 稳定输出 |
| **`minimax-h3-ref`** | `video_minimax_h3_r2v_official_turbo4.json` | 官方 Ref2VA 参考图 | **固定 4 步** | 否 | 官方 4 步极速多参考图+配音演播，短平快生成对话镜头 |
| **`minimax-h3-ref-turbo8`** | `video_minimax_h3_r2v_official_turbo8.json` | 官方 Ref2VA 参考图 | **固定 8 步** | 否 | 官方 8 步参考图演播，768p 细节充沛，人物表现力优异 |
| **`minimax-h3-ref-turbo4-face-refine`** | `video_minimax_h3_r2v_turbo4_face_refine.json` | 官方 Ref2VA + 人脸修复 | **各 4 步** | **二阶段** | **4 步极速 + 人脸修复**，兼得高生成速度与高人脸稳定性 |
| **`minimax-h3-ref-turbo8-face-refine`** | `video_minimax_h3_r2v_turbo8_face_refine.json` | 官方 Ref2VA + 人脸修复 | **各 8 步** | **二阶段** | **8 步高质 + 人脸修复**，影视级高质量近景、特写镜头标配 |
| **`minimax-h3-fl2va`** | `minimax_h3_fl2va_fast.json` | 原 16GB HiFi 首尾帧 | 默认 20 步 | 否 | 原开源 16GB 量化版，配合 SolAttention 算子节约显存 |
| **`minimax-h3-ref2va`** | `minimax_h3_ref2va_fast.json` | 原 16GB HiFi 参考图 | 默认 20 步 | 否 | 原开源 16GB 多参考图+台词演播路线 |
| **`minimax-h3-face-refine`** | `minimax-h3-face-refine.json` | 原 16GB HiFi + 人脸修复 | 基础 20 步 | **二阶段** | 原 HiFi 路线的多参考图与二阶段人脸修复组合 |

> 💡 **提示**：Turbo 入口均与专用 LoRA 权重步数强绑定，通用 `steps` 参数不会破坏四步/八步的固定约定；如需使用带人脸修复（Face Refine）的入口，请确保 ComfyUI 端已安装对应插件与模型。详细工作流接入手册请参阅 [ComfyUI 工作流配置指南](comfyUI_workflow/README.md)。

---

## 💻 运行环境与硬件配置要求 (System Requirements)

| 配置项 | 最低要求 (Minimum) | 推荐配置 (Recommended) | 说明 |
| :--- | :--- | :--- | :--- |
| **GPU 独立显卡** | **16 GB 显存**（如 RTX 4080 16G / RTX 3090 24G 等） | **16 GB 以上显存**（如 RTX 4090 24G 或专业级工作站显卡） | FLUX.2 Klein 与 MiniMax H3 影视级视频 DiT 大权重加载与推理的硬性基准 |
| **宿主机内存 (RAM)** | **32 GB** | **64 GB 及以上** | 视频模型大权重加载与多阶段合流在 32G 下容易触碰虚拟内存交换上限，推荐 64G 获得最佳流水线流畅度 |
| **磁盘存储** | 预留 **100 GB** 以上（SSD 固态硬盘） | 预留 **200 GB** 以上（NVMe M.2 高速固态） | 存放基础模型权重（FLUX.2/H3/CLIP/VAE）、本地缓存与高清视频渲染资产 |
| **操作系统** | Windows 10/11 64位 或 Linux (Ubuntu 20.04/22.04 LTS) | Windows 11 / Linux (Ubuntu 22.04+ LTS) | 支持 NVIDIA 专有驱动、CUDA 环境与 Docker 编排 |
| **基础软件** | **Docker**（或 Docker Desktop） | **Docker Compose v2** | 用于 Freyja 核心服务集群的一键拉起与管理 |

> 💡 **轻量化/云端分工方案（强烈推荐）**：
> - **轻量主机 / 笔记本**：可以只运行 **Freyja 核心服务**（仅需 2~4GB 内存即可流畅运行内部 Agent、MCP 服务、短剧剧本大纲、资产管理与大模型拆解）；
> - **GPU 算力主机**：将配备 16G+ 显存的独显电脑或云端 GPU 服务器仅作为 **ComfyUI 外部算力机**，Freyja 通过局域网或公网远程调度调用！两者彻底解耦、互不挤占资源。

---

## 🚀 三步极速上手部署全流程 (Quickstart)

本项目采用 **“Freyja 核心业务容器化 + 外部 ComfyUI 算力即插即用”** 的解耦架构。您只需按以下 **三步** 即可快速拉起完整短剧创作环境：

---

### 🌟 第一步：部署 Freyja 核心服务 (Docker / Docker Hub)

#### 1. 明确配置文件 (`.env`)
克隆本项目后，在根目录下复制配置模板：
```bash
cp .env.example .env
```
`.env` 核心字段说明：

| 配置项 | 默认值 | 作用与配置说明 |
| :--- | :--- | :--- |
| `IMAGE_PREFIX` | `astrag1/` | **Docker Hub 镜像命名空间**。默认直接拉取官方预编译镜像，免去本地漫长编译。 |
| `FREYJA_VERSION` | `1.0.0` | **镜像发布版本号**（支持 `1.0.0` 或 `latest`）。 |
| `COMFYUI_HTTP_URL` | `http://host.docker.internal:8188` | **外部 ComfyUI HTTP 地址**。<br>• ComfyUI 运行在**本机**（Windows 秋叶包/本机 Linux）：保持默认 `http://host.docker.internal:8188`；<br>• 运行在**独立局域网或云端 GPU 服务器**：修改为真实 IP，如 `http://192.168.1.100:8188`。 |
| `COMFYUI_WS_URL` | `ws://host.docker.internal:8188/ws` | **外部 ComfyUI WebSocket 地址**（规则同上，端口 `8188/ws`）。 |
| `FRONTEND_PORT` | `80` | 前端 Web 创作工作台访问端口。 |
| `BACKEND_PORT` | `8080` | Spring Boot 后端 API 接口端口。 |
| `GATEWAY_PORT` | `8000` | FastAPI 算力调度网关端口（OpenAPI 在 `/docs`）。 |
| `MINIO_EXTERNAL_ENDPOINT` | `http://localhost:9000` | **浏览器访问 MinIO 预览下载图片的地址**。公网部署请改为您的服务器公网 IP 或域名。 |
| `MYSQL_ROOT_PASSWORD` | `root` | 数据库密码（初次启动由 Docker 自动建库建表与插入基础字典）。 |
| `REDIS_PASSWORD` | `123456` | Redis 缓存密码。 |
| `FREYJA_CRYPTO_KEY` | `32位密钥` | 后端 API Key 加密存储 AES 秘钥，生产环境建议更换。 |

#### 2. 启动服务集群 (二选一)
- **方案 A：直接拉取 Docker Hub 预编译镜像 (⭐️ 强烈推荐，秒级部署，免本地编译)**：
  ```bash
  # 一键拉取最新镜像并后台启动
  docker compose pull
  docker compose up -d
  ```
  > 几秒内即可下载完成并启动，无需在宿主机安装任何 JDK 26、Node.js、MySQL 等环境！首次启动内置 `db-upgrade` 容器将全自动完成 MCP 与 Agent 相关增量表迁移。

- **方案 B：本地自动化脚本或源码构建**：
  - Windows 用户：直接双击根目录下的 **[`docker-start.bat`](./docker-start.bat)**；
  - Linux / macOS 用户：执行 `chmod +x docker-start.sh && ./docker-start.sh`；
  - 或执行源码编译：`docker compose up -d --build`。

#### 3. 核心服务访问入口
容器启动成功后，浏览器直接打开：

| 服务名称 | 访问地址 | 默认账号 / 密码 / 说明 |
| :--- | :--- | :--- |
| **🎬 Freyja 创作工作台** | **[http://localhost](http://localhost)** | 前端主界面（内部 Agent / 剧作大纲 / 资产工坊 / 抽卡工坊） |
| **⚙️ 后端 RESTful 接口** | [http://localhost:8080](http://localhost:8080) | Spring Boot 业务端点与 MCP 端点 (`/mcp`) |
| **⚡ FastAPI 调度网关** | [http://localhost:8000/docs](http://localhost:8000/docs) | OpenAPI 交互文档与工作流映射看板 |
| **📦 MinIO 对象存储** | [http://localhost:9001](http://localhost:9001) | 用户名: `minioadmin` ｜ 密码: `minioadmin123` |
| **🗄️ MySQL 数据库** | `localhost:3306` | 用户名: `root` ｜ 密码: `root`（库名: `freyja`） |
| **⚡ Redis 缓存** | `localhost:6379` | 密码: `123456` |

---

### 🌟 第二步：部署 ComfyUI 外部算力机 (Windows 秋叶包 / Linux GPU)

ComfyUI 作为独立的图像与视频生成算力节点，运行在配备 NVIDIA 独显（建议 $\ge 16	ext{ GB}$ 显存）的机器上：

#### 1. 开启外部网络访问监听 (必须)
为了让 Docker 容器内的网关能向 ComfyUI 下发生成任务与读取进度，ComfyUI 必须允许跨机器/跨容器访问：
- **Windows 秋叶启动器用户**：
  打开秋叶启动器界面 -> 点击左侧【高级设置】 -> 勾选 **“允许局域网访问 (0.0.0.0)”** 以及 **“开启 CORS 跨域”**，默认监听端口为 `8188`；
- **官方便携版 / 命令行启动用户**：
  启动命令追加参数：
  ```bash
  python main.py --listen 0.0.0.0 --port 8188 --enable-cors-header
  ```

#### 2. 安装必要插件
- **ComfyUI-VideoHelperSuite (`VHS`)**：处理视频合流与原生音频；
- **ComfyUI-Frame-Interpolation**：处理 RIFE 补帧；
- **ComfyUI-H3-FaceRefine**：二阶段人脸修复核心插件（下载至 `custom_nodes/` 并安装 requirements）；
- **ComfyUI-sol-attn**：原 16GB HiFi 工作流专属注意力显存优化插件（官方 INT8 工作流无需安装）。

---

### 🌟 第三步：下载模型权重并放入对应目录 (Model Downloads)

将所选工作流需要的权重放入 **运行 ComfyUI 的 GPU 算力机** 的对应目录（`ComfyUI/models/`）。

#### 1. 图像生成与官方 MiniMax H3 权重清单

| 分类 | 推荐/默认文件名 | 存放路径 (ComfyUI目录下) | 官方/开源下载来源 | 用途说明 |
| :--- | :--- | :--- | :--- | :--- |
| **生图主干** | `flux-2-klein-9b.safetensors` | `models/unet/` | HuggingFace / 镜像站 | FLUX.2 Klein 9B 角色设定图、造型设计图生成 |
| **生图编码器** | `qwen_3_8b_fp8mixed.safetensors` | `models/clip/` | HuggingFace / 镜像站 | FLUX.2 文本提示词特征提取编码器 |
| **生图 VAE** | `flux2-vae.safetensors` | `models/vae/` | HuggingFace / 镜像站 | FLUX.2 图像潜空间编解码器 |
| **官方 FL2VA 主模型** | `minimax_h3_fl2va_pruned_int8_convrot.safetensors` | `models/diffusion_models/` | [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/diffusion_models) | 官方文生 / 首帧 / 首尾帧视频与伴生音频 |
| **官方 Ref2VA 主模型** | `minimax_h3_ref2va_pruned_int8_convrot.safetensors` | `models/diffusion_models/` | [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/diffusion_models) | 官方多参考图 / 配音演播与人脸修复 |
| **官方 H3 文本编码器** | `qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors` | `models/text_encoders/` | [官方文本编码器](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/text_encoders) | FL2VA / Ref2VA 共享的 32B 多模态编码器 |
| **官方视频 VAE** | `minimax_h3_video_vae_int8_convrot.safetensors` | `models/vae/` | [官方 VAE](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/vae) | 官方视频潜空间编解码 |
| **H3 音频 VAE** | `minimax_h3_audio_vae_fp32.safetensors` | `models/vae/` | [官方 VAE](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/vae) | H3 原生伴生音频潜空间编解码 |

#### 2. Turbo 加速 LoRA 与人脸修复专用权重

| 用途 | 文件 / 模型包 | 存放目录 | 下载来源与使用说明 |
| :--- | :--- | :--- | :--- |
| **FL2VA Turbo4 (4步)** | `minimax_h3_fl2v_turbo_4step_v1.0_768p_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/loras)，固定 4 步，768p |
| **FL2VA Turbo8 (8步)** | `minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/loras)，固定 8 步，544p |
| **Ref2VA Turbo4 (4步)** | `minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/loras)，固定 4 步 |
| **Ref2VA Turbo8 (8步)** | `minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors` | `models/loras/` | [LightX2V](https://huggingface.co/lightx2v/Minimax-h3-Turbo)，固定 8 步，768p |
| **人脸检测模型** | `face_yolov8m.pt` | `models/ultralytics/bbox/` | [Bingsu/adetailer](https://huggingface.co/Bingsu/adetailer)，FaceRefine 专用检测器 |
| **人脸身份向量匹配** | InsightFace `buffalo_l` 模型包 | `models/insightface/models/buffalo_l/` | 身份锚定模型，离线 GPU 节点建议提前放置 |

#### 3. 视频后处理模型

| 模型名称 | 推荐/默认文件名 | 存放目录 | 下载来源与说明 |
| :--- | :--- | :--- | :--- |
| **SeedVR2 3B INT8** | `seedvr2_3b_int8_convrot.safetensors` | `models/diffusion_models/` | [Comfy-Org/SeedVR2](https://huggingface.co/Comfy-Org/SeedVR2)，原生视频超分与时间分块 |
| **SeedVR2 VAE** | `seedvr2_ema_vae_fp16.safetensors` | `models/vae/` | SeedVR2 视频潜空间编解码 |
| **RIFE 4.9 插帧** | `rife49.pth` | `custom_nodes/ComfyUI-Frame-Interpolation/ckpts/rife/` | 视频 24 FPS 转 48 FPS 平滑补帧 |
| **RealESRGAN x2** | `RealESRGAN_x2plus.pth` | `models/upscale_models/` | 经典 2 倍超分辨率无损放大 |

#### 4. 原 16GB HiFi 兼容模型 (选装)
若您选用原 16GB HiFi 工作流，请在 `models/unet/` 准备 `minimax_h3_fl2va_dit_16g.safetensors` 与 `minimax_h3_ref2va_dit_16g.safetensors`，并在 `models/clip/` 放置 `qwen3vl_text_encoder.safetensors`（来源于 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)）。

---

## 🛠️ 核心业务模块全览

### 1. 👥 角色多造型与全生命周期视觉一致性
- **角色身份层与造型层解耦**：角色表（`res_character`）维护基础信息与生物特征，造型表（`res_character_look`）维护服装、发型与特定形态，支持默认造型与互斥流转；
- **实体注册表与跨集消歧**：五级消歧流水线自动识别小说代词（如“少年”、“他”、“那道身影”），结合 Character Blackboard 角色看板分发，确保跨场景、跨集数的人物外观高保真一致；
- **专属音色克隆工坊**：集成 AI 角色专属音色设计工坊 (`VoiceDesignModal.vue`)，支持母音设计、提供商绑定与台词声音克隆。

### 2. 🎬 视听导演规划与双轨提示词衍生 (DirectorPlan)
- **物理机位结构化**：严格区分景别（Shot Type）与运镜方式（Camera Movement），支持 `AUTO` 智能决策与创作者手动调整；
- **Camera Beats 运镜时间轴**：将复杂叙事节奏解构为结构化运镜节拍；
- **MiniMax H3 双轨转译**：全自动将视听规划转译为首尾帧三段式 `videoPrompt` 与全参考六段式 `prompt`，纯结构化校验，杜绝字符串模糊猜谜。

### 3. 📑 剧本并行分段拆解流水线 (Parallel Script Decompose)
- **Planner AI**：全章小说深度理解，精准执行剧情节奏分段（Story Segments），提取出场角色与环境实体；
- **Worker AI × N 并发**：聚焦分段镜头剧本创作与视听规划；
- **Java 确定性合并**：服务端负责镜头序号重排、防碎镜碎片率统计与级联持久化。

### 4. 🎞️ 分镜抽卡版本与视频后期处理 (Take History & Video Processing)
- **分镜抽卡版本追溯 (Takes)**：单个分镜支持多次生成并完整保留历史版本（Take），支持一键切换并联动失效下游尾帧缓存；
- **视频超分与补帧**：无缝对接 SeedVR2、RealESRGAN 与 RIFE 4.9，支持以分镜或指定 Take 作为源头，参数由模型中心动态驱动，完全告别硬编码。

### 5. 🧩 标准 Agent Skill 领域知识资产库
系统内置 5 个经过多轮调优的标准 Agent Skill 资源包（存放于 `skills/`），启动时全自动校验导入：
1. **`character-disambiguation`**：Planner 角色消歧与代词归一（强制依赖）；
2. **`character-sheet-design`**：人物与造型设定图三视图规范；
3. **`camera-direction`**：Worker 连续剧情分镜编排与世界状态继承；
4. **`cinematography`**：视觉导演、机位投影与 H3 动作语言编译；
5. **`h3-prompt-writing`**：MiniMax H3 影视级双轨提示词编写规范。

---

## 🏛️ 系统架构拓扑

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        Vue 3 前端 Web 创作工作台                       │
│  ├─ 内部 Agent 对话工坊 (需求澄清 / 剧本拆解 / 制作准备 / Codex Pet)    │
│  ├─ 剧作大纲卡片流工作台 (四层大纲 / 连续镜头组 / DirectorPlan 规划)   │
│  ├─ 资产工坊 (角色多造型 / 专属音色设计 / 场景与道具资产管理)          │
│  └─ 视频后期中心 (抽卡版本追溯 Takes / SeedVR2 与 RealESRGAN 超分 / RIFE)│
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │ HTTP RESTful / WebSocket       │ Streamable HTTP (MCP)
                    │                                │
                    ▼                                ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    Spring Boot 4 后端业务核心 (JDK 26)                  │
│                                                                        │
│  ┌─────────────────────────────┐    ┌────────────────────────────────┐  │
│  │    内部 Agent 执行中心      │    │     统一外部 MCP Server 服务   │  │
│  │  - 会话管理 / 规划看板      │    │  - 72+ 原子化业务 CRUD 工具    │  │
│  │  - 思考协议 / 动态预算      │    │  - 资产参考图上传 / Skills 调阅│  │
│  │  - 检查点持久化与中断恢复   │    │  - 令牌认证与细粒度项目权限    │  │
│  └──────────────┬──────────────┘    └────────────────┬───────────────┘  │
│                 │                                    │                  │
│                 └─────────────────┬──────────────────┘                  │
│                                   ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │          统一共享能力层与创作规则中心 (/system/ai-capabilities)     │  │
│  │   - Spring AI 智能引擎 (Planner / Worker / DirectorPlan 导演规划) │  │
│  │   - 角色实体注册看板 (Character Blackboard / 跨集消歧流水线)       │  │
│  │   - 动态配置引擎 (SysConfig / 动态系统提示词)                     │  │
│  │   - 双轨资产归档服务 (MinIO 对象存储 / 本地剪辑同步 / FFmpeg 尾帧)│  │
│  └────────────────────────────────┬─────────────────────────────────┘  │
└───────────────────────────────────┼────────────────────────────────────┘
                                    │ HTTP / WebSocket 算力调度
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       FastAPI 算力调度网关 (Port 8000)                  │
│  - 工作流模板引擎与动态参数映射 (OpenAI 兼容生图 / 视频生成适配器)     │
│  - 官方 MiniMax H3 (FL2VA / Ref2VA) 调度与原生音频提取                 │
│  - LightX2V Turbo4 / Turbo8 极速生成与步数约束                        │
│  - ComfyUI-H3-FaceRefine 二阶段人脸追踪、重绘与融合贴回                │
│  - 异步任务状态追踪与 WebSocket 进度全双工广播                         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ ComfyUI API (Port 8188)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   外部 ComfyUI 独立 GPU 算力机节点                     │
│    (配备 NVIDIA 16G+ 独显，运行在 Windows 秋叶包或 Linux GPU 服务器上) │
│  - FLUX.2 Klein 9B 图像主干 ｜ Qwen3-VL 32B NVFP4 AWQ 文本编码器       │
│  - 官方 H3 INT8 ConvRot 视频 DiT ｜ Turbo4 / Turbo8 LoRA 极速采样      │
│  - YOLOv8m 人脸检测 ｜ InsightFace 身份锚定 ｜ SeedVR2 超分 ｜ RIFE 补帧│
└────────────────────────────────────────────────────────────────────────┘
```

---

## ⚙️ Docker 部署进阶指南

### 1. 常用运维管理命令
```bash
# 查看所有容器运行状态与健康检查
docker compose ps

# 查看后端实时日志
docker compose logs -f backend

# 查看网关调度日志
docker compose logs -f comfy-gateway

# 安全停止全部服务 (保留数据卷与资产)
docker compose stop

# 彻底清理并重置系统与数据库 (⚠️ 会删除物理数据卷，谨慎使用)
docker compose down -v
```

### 2. 生产环境网络避坑指南
> [!WARNING]
> **Docker 容器环境下，AI 提供商 Base URL 切勿填写 `127.0.0.1` 或 `localhost`！**
> - **容器间通信推荐**：填 `http://comfy-gateway:8000/v1`（利用 Docker 内部 DNS）；
> - **本机混合调试推荐**：填 `http://host.docker.internal:8000/v1`；
> - 系统内置智能防呆逻辑：若检测到运行在 Docker 内部且用户误填了 `127.0.0.1:8000`，后端将自动智能纠偏重定向至 `http://comfy-gateway:8000`，防止任务中断。

---

## 🛠️ 本地开发环境技术栈

- **后端**：Java 26 + Maven 3.9+ + Spring Boot 4.1.1 + MyBatis-Plus 3.5.17 + Spring AI 2.0.1
- **前端**：Node.js 22+ + Vite 6 + Vue 3.5 + TypeScript + Element Plus + Tailwind CSS + Pinia
- **网关**：Python 3.10+ + FastAPI + Uvicorn
- **协议与中间件**：Model Context Protocol (MCP Streamable HTTP) + MySQL 8.0 + Redis 7 + MinIO

---

## 💬 现状、已知问题与共建呼吁

1. **关于 Bug 反馈与维护说明**：
   - 欢迎在 GitHub 提交 [Issues](https://github.com/Elicit1/freyja/issues) 或 Pull Request；
   - 由于作者平时工作繁忙，可能无法实时回复，欢迎社区开发者共同参与共建！
2. **核心 Skill 持续优化**：
   - 系统内置的 **`cinematography`**（视觉导演、机位投影与动作语言）和 **`camera-direction`**（连续剧情分镜编排）是决定全流程分镜专业感的关键基石；
   - 诚邀影视编导、摄影专业的大佬针对 `skills/` 中的规约文档持续提出建议和 PR！

<p align="center">
  <img src="image/freyja1.png" alt="Freyja Mascot" width="300" />
</p>

---

## 📄 开源许可证

本项目基于 [Apache 2.0 License](LICENSE) 协议开源。
