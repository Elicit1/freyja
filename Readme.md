<h1 align="center">🎬 FREYJA AI DIGITAL STUDIO</h1>

<p align="center">
  <strong>基于 Spring Boot 4 + Vue 3 的 AI 短剧分镜管理与 ComfyUI 调度系统</strong>
  <br />
  内部 Agent ｜ 外部 MCP ｜ MiniMax H3 官方模型 ｜ 二阶段人脸修复 ｜ 4/8-Step 加速
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/JDK-26-orange.svg" alt="JDK 26">
  <img src="https://img.shields.io/badge/Vue-3.5-42b883.svg" alt="Vue 3">
  <img src="https://img.shields.io/badge/MCP-Streamable%20HTTP-blueviolet.svg" alt="MCP">
  <img src="https://img.shields.io/badge/Agent-Internal-ff69b4.svg" alt="Internal Agent">
  <img src="https://img.shields.io/badge/Video%20Model-MiniMax%20H3-critical.svg" alt="MiniMax H3">
  <img src="https://img.shields.io/badge/Acceleration-Turbo%204%2F8%20Step-yellow.svg" alt="Turbo 4/8 Step">
  <img src="https://img.shields.io/badge/Face%20Refine-Supported-success.svg" alt="Face Refinement">
  <img src="https://img.shields.io/badge/Docker-Compose%20v2-2496ed.svg" alt="Docker">
</p>

<br />

![FREYJA AI DIGITAL STUDIO](./image/freyja2.png)

<br />

---

## 📖 简介

**Freyja** 是一个面向 AI 短剧分镜制作与 ComfyUI 算力调度的开源管理系统。系统支持小说剧本拆解、四层大纲管理、角色与多造型管理、视听导演规划与提示词生成、分镜视频生成与版本管理，以及视频超分与插帧后处理。

### 核心功能

1. **内部 Agent**：在工作台直接与内置 Agent 对话交互，完成小说创作、剧本分段拆解入库、资产梳理及制作准备；支持任务规划展示、进度追踪、思考协议（DeepSeek / MiMo）与上下文摘要。
2. **外部 MCP 服务**：基于 Model Context Protocol (Streamable HTTP) 提供对外工具接口，外部 AI 客户端（如 Claude Desktop、Cursor、Cline 等）可直接读取并编辑项目大纲、资产与提示词。
3. **MiniMax H3 官方视频模型**：支持接入官方 FL2VA（首尾帧生视频）与 Ref2VA（多参考图+配音驱动演播）工作流，生成画面与原生伴生音频。
4. **4-Step / 8-Step 加速模型**：支持 LightX2V 蒸馏 LoRA，将生成步数设为固定 4 步或 8 步，缩短单镜头渲染耗时。
5. **二阶段人脸修复**：支持集成 ComfyUI-H3-FaceRefine 工作流，基于 YOLOv8m 人脸检测定位与 InsightFace 身份特征提取，对生成视频进行逐帧追踪重绘修复，可与基础模型及加速模型组合使用。

---

## 🛠️ 功能介绍与使用指南

### 1. 内部 Agent

#### 功能说明
内部 Agent 提供对话式短剧协同创作能力，支持：
- 通过多轮对话进行小说大纲整理、角色/场景资产提取与剧本拆解入库；
- 查看任务执行计划与实时步骤进度；
- 兼容 DeepSeek Chat 与 MiMo Chat 等思考协议；
- 会话达到上下文预算时自动生成历史摘要并保存检查点。

#### 使用方法
1. 进入【系统管理 → AI 提供商管理】，配置支持 Tool Calling 的 Chat 模型 API Key；
2. 进入【系统管理 → Agent 配置】（`/system/agent`），选择模型，勾选所需工具与 Skills 知识包，配置运行预算后保存并启用；
3. 点击工作台的 Agent 入口打开对话面板，输入创作指令（例如：“读取当前项目资产，并将提供的小说章节拆解为连续分镜入库”）；
4. 详细配置项说明参见 [Agent 配置与接口文档](docs/agent-api.md)。

---

### 2. 外部 MCP 服务

#### 功能说明
外部 MCP 服务基于标准 Streamable HTTP 协议运行，为外部 AI 客户端开放系统能力：
- 提供涵盖项目、剧集、场次、分镜、角色、造型、场景、道具、关键帧的查询与 CRUD 工具；
- 支持参考图上传与已发布 Skills 知识包调阅；
- 支持在后台配置访问令牌、限制可访问的项目范围、控制写入权限与工具开关。

#### 使用方法
1. 进入【系统管理 → MCP 服务】（`/system/mcp`），生成随机访问令牌（Bearer Token），选择允许访问的项目与工具权限，开启 MCP 服务；
2. 在外部 AI 客户端中添加连接配置：

**TOML 格式客户端（如 Claude Desktop）：**
```toml
[mcp_servers.freyja]
url = "http://localhost/api/mcp"
bearer_token_env_var = "FREYJA_MCP_TOKEN"
tool_timeout_sec = 300
```
*在环境变量中设置 `FREYJA_MCP_TOKEN` 为后台配置的同一令牌。*

**JSON 格式客户端（如 Cursor / Cline / Windsurf）：**
```json
{
  "mcpServers": {
    "freyja": {
      "url": "http://localhost/api/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_MCP_TOKEN"
      },
      "timeout": 300
    }
  }
}
```
*直连后端容器时，URL 填写 `http://localhost:8080/mcp`。*

3. 连接成功后即可通过外部客户端直接管理项目数据，接口说明参见 [MCP API 说明文档](docs/mcp-api.md)。

---

### 3. MiniMax H3 视频生成、加速与人脸修复

#### 功能说明
系统通过 FastAPI 网关调度 ComfyUI 算力节点，支持多种视频生成与增强工作流：
- **首尾帧模式 (FL2VA)**：依据起始首帧与结束尾帧生成连贯镜头视频；
- **多参考图模式 (Ref2VA)**：结合角色设定图、场景图与音频生成演播视频；
- **Turbo 加速 (4步/8步)**：挂载对应 LoRA 权重，固定采样为 4 步或 8 步执行极速生成；
- **二阶段人脸修复**：生成阶段完成后，调用 YOLOv8m 与 InsightFace 对人脸区域逐帧提取、重绘并贴回原视频。

#### 预置工作流入口列表
在 [`comfy_gateway/nodes/machine_default.yaml`](comfy_gateway/nodes/machine_default.yaml) 中已预置以下 9 个模型入口，可在分镜渲染工坊直接选择：

| 网关模型编码 | UI 工作流文件 (`comfyUI_workflow/`) | 生成路线 | 采样步数 | 人脸修复 | 适用场景 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`minimax-h3-fl2va-turbo4`** | `video_minimax_h3_i2v_official_turbo4.json` | 官方 FL2VA 首尾帧 | 固定 4 步 | 否 | 768p 首尾帧极速生成 |
| **`minimax-h3-fl2va-turbo8`** | `video_minimax_h3_i2v_official_turbo8.json` | 官方 FL2VA 首尾帧 | 固定 8 步 | 否 | 544p/768p 首尾帧生成 |
| **`minimax-h3-ref`** | `video_minimax_h3_r2v_official_turbo4.json` | 官方 Ref2VA 参考图 | 固定 4 步 | 否 | 4 步多参考图与配音演播 |
| **`minimax-h3-ref-turbo8`** | `video_minimax_h3_r2v_official_turbo8.json` | 官方 Ref2VA 参考图 | 固定 8 步 | 否 | 8 步 768p 多参考图演播 |
| **`minimax-h3-ref-turbo4-face-refine`** | `video_minimax_h3_r2v_turbo4_face_refine.json` | 官方 Ref2VA + 人脸修复 | 各 4 步 | 二阶段 | 4 步极速生成并包含人脸修复 |
| **`minimax-h3-ref-turbo8-face-refine`** | `video_minimax_h3_r2v_turbo8_face_refine.json` | 官方 Ref2VA + 人脸修复 | 各 8 步 | 二阶段 | 8 步高清生成并包含人脸修复 |
| **`minimax-h3-fl2va`** | `minimax_h3_fl2va_fast.json` | 16GB HiFi 首尾帧 | 默认 20 步 | 否 | 16GB 显存首尾帧生成路线 |
| **`minimax-h3-ref2va`** | `minimax_h3_ref2va_fast.json` | 16GB HiFi 参考图 | 默认 20 步 | 否 | 16GB 显存参考图演播路线 |
| **`minimax-h3-face-refine`** | `minimax-h3-face-refine.json` | 16GB HiFi + 人脸修复 | 基础 20 步 | 二阶段 | 16GB 显存路线带二阶段人脸修复 |

#### 使用方法
1. 根据下文模型清单在 ComfyUI 节点部署对应模型与插件；
2. 在【AI 提供商与模型管理】中登记网关模型编码；
3. 打开分镜卡片，进入渲染工坊，选择对应模型编码并提交生成任务。

---

### 4. 角色资产与视觉一致性管理

#### 功能说明
- **身份层与造型层解耦**：角色基础档案（`res_character`）与外观造型（`res_character_look`）分离管理，支持指定默认造型与多套外观设计图；
- **跨集代词消歧流水线**：基于角色别名表（`res_character_alias`）与实体看板，识别文本中的角色指代并绑定系统角色；
- **专属音色克隆工坊**：支持为角色设计母音并绑定 TTS 语音模型，用于分镜台词合成。

---

### 5. 视听导演规划与双轨提示词 (DirectorPlan)

#### 功能说明
- **结构化视听机位**：支持景别（Shot Type）与运镜方式（Camera Movement）结构化配置；
- **Camera Beats 时间轴**：按镜头时间线分段记录运镜节拍；
- **双轨提示词衍生**：自动将分镜机位、动作与台词转译为首尾帧三段式 `videoPrompt` 或参考图六段式 `prompt`。

---

### 6. 分镜抽卡版本与视频后处理

#### 功能说明
- **抽卡历史管理 (Takes)**：同一分镜支持保留多次生成的视频版本，支持自由切换选定版本；
- **视频超分与补帧**：集成 SeedVR2 3B INT8、RealESRGAN x2 视频放大与 RIFE 4.9 插帧（24 FPS 转 48 FPS）。

---

### 7. AI Skill 知识资产管理

#### 功能说明
系统内置 5 个标准 Agent Skill 知识包（存放于 `skills/` 目录），启动时自动导入：
- `character-disambiguation`：角色消歧与实体映射规约；
- `character-sheet-design`：人物与造型设定图规范；
- `camera-direction`：分镜连续性与剧情编排规约；
- `cinematography`：视听机位选择与动作转译规约；
- `h3-prompt-writing`：MiniMax H3 双轨提示词格式规范。
用户可在【系统管理 → AI 技能管理】中上传 ZIP 包更新或新增自定义技能。

---

## 💻 运行环境与硬件配置要求

| 配置项 | 最低要求 | 推荐配置 | 说明 |
| :--- | :--- | :--- | :--- |
| **GPU 显存** | **16 GB**（如 RTX 4080 16G / RTX 3090 24G） | **16 GB 以上**（如 RTX 4090 24G） | 用于 FLUX.2 生图与 MiniMax H3 视频生成 |
| **宿主机内存** | **32 GB** | **64 GB 及以上** | 视频模型大权重加载与多阶段处理推荐 64G 内存 |
| **磁盘存储** | 预留 **100 GB** 以上（SSD） | 预留 **200 GB** 以上（NVMe M.2 SSD） | 用于存放基础模型权重与生成资产 |
| **操作系统** | Windows 10/11 64位 或 Ubuntu 20.04/22.04 | Windows 11 / Ubuntu 22.04+ | 需支持 NVIDIA 专有驱动与 Docker 环境 |
| **基础软件** | **Docker**（或 Docker Desktop） | **Docker Compose v2** | 用于 Freyja 核心服务的一键拉起与管理 |

> 💡 **架构分工方案**：
> - **轻量主机 / 笔记本**：运行 Freyja 核心服务容器（约需 2~4GB 内存）；
> - **GPU 算力主机**：独立运行 ComfyUI 算力节点，Freyja 通过局域网或公网调用，两者相互独立。

---

## 🚀 三步快速部署指南

---

### 第一步：部署 Freyja 核心服务 (Docker)

#### 1. 复制配置文件模板
```bash
cp .env.example .env
```
`.env` 常用配置项说明：

| 配置项 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `IMAGE_PREFIX` | `astrag1/` | Docker Hub 镜像前缀 |
| `FREYJA_VERSION` | `1.0.0` | 镜像版本标签 |
| `COMFYUI_HTTP_URL` | `http://host.docker.internal:8188` | ComfyUI HTTP 地址（跨机器运行填写真实局域网 IP） |
| `COMFYUI_WS_URL` | `ws://host.docker.internal:8188/ws` | ComfyUI WebSocket 地址 |
| `FRONTEND_PORT` | `80` | 前端访问端口 |
| `BACKEND_PORT` | `8080` | 后端 API 与 MCP 接口端口 |
| `GATEWAY_PORT` | `8000` | FastAPI 算力网关端口 |
| `MINIO_EXTERNAL_ENDPOINT` | `http://localhost:9000` | 浏览器访问 MinIO 图片的地址 |
| `MYSQL_ROOT_PASSWORD` | `root` | 数据库密码 |
| `REDIS_PASSWORD` | `123456` | Redis 密码 |

#### 2. 启动服务
- **拉取镜像启动（推荐）**：
  ```bash
  docker compose pull
  docker compose up -d
  ```
- **本地构建启动**：
  - Windows：运行根目录下 `docker-start.bat`
  - Linux / macOS：运行 `./docker-start.sh` 或 `docker compose up -d --build`

#### 3. 访问入口

| 服务名称 | 访问地址 | 说明 |
| :--- | :--- | :--- |
| **前端工作台** | [http://localhost](http://localhost) | 主界面（Agent / 剧本大纲 / 资产 / 渲染） |
| **后端接口 / MCP** | [http://localhost:8080](http://localhost:8080) | 业务接口与 MCP 端点 (`/mcp`) |
| **FastAPI 网关** | [http://localhost:8000/docs](http://localhost:8000/docs) | 网关 OpenAPI 文档与工作流看板 |
| **MinIO 控制台** | [http://localhost:9001](http://localhost:9001) | 账号: `minioadmin` ｜ 密码: `minioadmin123` |

---

### 第二步：部署 ComfyUI 外部算力节点

在配备 NVIDIA 独显（显存 $\ge 16	ext{ GB}$）的机器上启动 ComfyUI：

#### 1. 开启网络监听
- **Windows 秋叶启动器**：高级设置中勾选 **“允许局域网访问 (0.0.0.0)”** 与 **“开启 CORS 跨域”**，端口为 `8188`；
- **命令行启动**：添加启动参数 `--listen 0.0.0.0 --port 8188 --enable-cors-header`。

#### 2. 安装必要插件
- **ComfyUI-VideoHelperSuite (`VHS`)**：音视频合流与帧管理；
- **ComfyUI-Frame-Interpolation**：RIFE 视频补帧；
- **ComfyUI-H3-FaceRefine**：二阶段人脸修复插件（放入 `custom_nodes/` 并安装对应 Python 依赖）；
- **ComfyUI-sol-attn**：原 16GB HiFi 工作流显存优化插件（使用官方 INT8 工作流时无需安装）。

---

### 第三步：下载模型权重并放入对应目录

将模型权重放入 ComfyUI 根目录的对应子文件夹中：

#### 1. 生图与官方 MiniMax H3 主模型

| 分类 | 文件名 | 存放路径 (ComfyUI目录下) | 下载来源 | 用途说明 |
| :--- | :--- | :--- | :--- | :--- |
| **生图主干** | `flux-2-klein-9b.safetensors` | `models/unet/` | HuggingFace / 镜像站 | FLUX.2 设定图与分镜图生成 |
| **生图编码器** | `qwen_3_8b_fp8mixed.safetensors` | `models/clip/` | HuggingFace / 镜像站 | FLUX.2 文本编码器 |
| **生图 VAE** | `flux2-vae.safetensors` | `models/vae/` | HuggingFace / 镜像站 | FLUX.2 图像 VAE |
| **官方 FL2VA 主模型** | `minimax_h3_fl2va_pruned_int8_convrot.safetensors` | `models/diffusion_models/` | [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3) | 官方文生/首帧/首尾帧视频 |
| **官方 Ref2VA 主模型** | `minimax_h3_ref2va_pruned_int8_convrot.safetensors` | `models/diffusion_models/` | [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3) | 官方多参考图视频与人脸修复 |
| **官方文本编码器** | `qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors` | `models/text_encoders/` | [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3) | 32B 多模态特征编码器 |
| **官方视频 VAE** | `minimax_h3_video_vae_int8_convrot.safetensors` | `models/vae/` | [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3) | 视频潜空间编解码 |
| **H3 音频 VAE** | `minimax_h3_audio_vae_fp32.safetensors` | `models/vae/` | [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3) | 原生伴生音频编解码 |

#### 2. Turbo 加速 LoRA 与人脸修复专用模型

| 用途 | 文件名 | 存放路径 | 下载来源与说明 |
| :--- | :--- | :--- | :--- |
| **FL2VA Turbo4** | `minimax_h3_fl2v_turbo_4step_v1.0_768p_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3)，固定 4 步 |
| **FL2VA Turbo8** | `minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3)，固定 8 步 |
| **Ref2VA Turbo4** | `minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3)，固定 4 步 |
| **Ref2VA Turbo8** | `minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors` | `models/loras/` | [LightX2V](https://huggingface.co/lightx2v/Minimax-h3-Turbo)，固定 8 步 |
| **人脸检测模型** | `face_yolov8m.pt` | `models/ultralytics/bbox/` | [Bingsu/adetailer](https://huggingface.co/Bingsu/adetailer) |
| **人脸身份匹配** | InsightFace `buffalo_l` 模型包 | `models/insightface/models/buffalo_l/` | 身份特征匹配模型 |

#### 3. 视频后处理模型

| 模型名称 | 文件名 | 存放路径 | 下载来源与说明 |
| :--- | :--- | :--- | :--- |
| **SeedVR2 3B INT8** | `seedvr2_3b_int8_convrot.safetensors` | `models/diffusion_models/` | [Comfy-Org/SeedVR2](https://huggingface.co/Comfy-Org/SeedVR2) |
| **SeedVR2 VAE** | `seedvr2_ema_vae_fp16.safetensors` | `models/vae/` | SeedVR2 潜空间编解码 |
| **RIFE 4.9 插帧** | `rife49.pth` | `custom_nodes/ComfyUI-Frame-Interpolation/ckpts/rife/` | 24 FPS 转 48 FPS 补帧 |
| **RealESRGAN x2** | `RealESRGAN_x2plus.pth` | `models/upscale_models/` | 2 倍视频超分辨率放大 |

#### 4. 原 16GB HiFi 兼容模型 (选装)
若使用原 16GB HiFi 工作流，在 `models/unet/` 放置 `minimax_h3_fl2va_dit_16g.safetensors` 与 `minimax_h3_ref2va_dit_16g.safetensors`，在 `models/clip/` 放置 `qwen3vl_text_encoder.safetensors`（来源于 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)）。

---

## 🏛️ 系统架构拓扑

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        Vue 3 前端 Web 创作工作台                       │
│  ├─ 内部 Agent 对话工坊 (需求对话 / 剧本拆解 / 制作准备)               │
│  ├─ 剧作大纲卡片流工作台 (四层大纲 / 连续镜头组 / 导演规划)            │
│  ├─ 资产工坊 (角色多造型 / 场景与道具资产 / 专属音色设计)              │
│  └─ 视频后期中心 (抽卡版本追溯 Takes / SeedVR2 与 RealESRGAN 超分 / RIFE)│
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │ HTTP RESTful / WebSocket       │ Streamable HTTP (MCP)
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
│  - 工作流模板引擎与参数映射 (OpenAI 兼容生图 / 视频生成适配器)         │
│  - 官方 MiniMax H3 (FL2VA / Ref2VA) 调度与原生音频提取                 │
│  - LightX2V Turbo4 / Turbo8 极速生成与步数约束                        │
│  - ComfyUI-H3-FaceRefine 二阶段人脸追踪、重绘与贴回                    │
│  - 异步任务状态追踪与 WebSocket 进度广播                               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ ComfyUI API (Port 8188)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   外部 ComfyUI 独立 GPU 算力机节点                     │
│    (配备 NVIDIA 16G+ 独显，运行在 Windows 秋叶包或 Linux GPU 服务器上) │
│  - FLUX.2 Klein 9B 图像主干 ｜ Qwen3-VL 32B NVFP4 AWQ 文本编码器       │
│  - 官方 H3 INT8 ConvRot 视频 DiT ｜ Turbo4 / Turbo8 LoRA 采样          │
│  - YOLOv8m 人脸检测 ｜ InsightFace 身份锚定 ｜ SeedVR2 超分 ｜ RIFE 补帧│
└────────────────────────────────────────────────────────────────────────┘
```

---

## ⚙️ 运维管理与配置说明

### 1. 常用命令
```bash
# 查看所有容器运行状态
docker compose ps

# 查看后端日志
docker compose logs -f backend

# 查看网关日志
docker compose logs -f comfy-gateway

# 停止服务 (保留数据卷)
docker compose stop

# 重置并清理容器与数据卷 (注意：会删除已有数据库数据卷)
docker compose down -v
```

### 2. 容器间通信配置说明
> [!NOTE]
> 在 Docker 容器环境中，后端与网关容器之间通信应使用服务名：
> - 容器间通信填 `http://comfy-gateway:8000/v1`；
> - 宿主机与容器混合调试填 `http://host.docker.internal:8000/v1`；
> - 切勿在容器内将 Base URL 配置为 `127.0.0.1` 或 `localhost`。

---

## 🛠️ 技术栈清单

- **后端**：Java 26 + Maven 3.9+ + Spring Boot 4.1.1 + MyBatis-Plus 3.5.17 + Spring AI 2.0.1
- **前端**：Node.js 22+ + Vite 6 + Vue 3.5 + TypeScript + Element Plus + Tailwind CSS + Pinia
- **网关**：Python 3.10+ + FastAPI + Uvicorn
- **协议与中间件**：Model Context Protocol (MCP Streamable HTTP) + MySQL 8.0 + Redis 7 + MinIO

---

## 💬 问题反馈与交流

- 遇到问题欢迎在 GitHub 提交 [Issues](https://github.com/Elicit1/freyja/issues) 或 Pull Request；
- 内置 Skill 规约（`skills/` 目录）支持自定义迭代，欢迎在实践中完善与优化。

<p align="center">
  <img src="image/freyja1.png" alt="Freyja Mascot" width="260" />
</p>

---

## 📄 开源许可证

本项目基于 [Apache 2.0 License](LICENSE) 协议开源。
