<h1 align="center">🎬 FREYJA AI DIGITAL STUDIO (MiniMax H3)</h1>

<p align="center">
  <strong>基于 Spring Boot 4 + Vue 3 + MiniMax H3 影视级模型的 AI 短剧分镜制作与 ComfyUI 调度平台</strong>
  <br />
  四层剧作大纲 ｜ 角色造型资产 ｜ 官方 H3 与人脸修复 ｜ 内置 Agent ｜ MCP 统一能力 ｜ 视频超分与补帧
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Video%20Model-MiniMax%20H3-critical.svg" alt="MiniMax H3">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/JDK-26-orange.svg" alt="JDK 26">
  <img src="https://img.shields.io/badge/Vue-3.5-42b883.svg" alt="Vue 3">
  <img src="https://img.shields.io/badge/Docker-Compose%20v2-2496ed.svg" alt="Docker">
  <img src="https://img.shields.io/badge/ComfyUI-Universal%20Gateway-blueviolet.svg" alt="ComfyUI">
</p>

<br />

![FREYJA AI DIGITAL STUDIO](./image/freyja2.png)

<br />

---

## 📖 简介

**Freyja** 是一个面向 AI 短剧影视级分镜工业化制作的开源管理与算力调度系统。

系统围绕短剧创作的全链路视听需求，深度融合大模型与影视级开源生成模型，提供以下 **核心创作功能**：

- 🎬 **首尾帧驱动影视镜头生成 (First-Last Frame Generation)**：支持为分镜精准指定起始首帧与结束尾帧，调用 MiniMax H3 FL2VA 模型生成具备高物理连贯性、自然运镜轨迹与逼真动效的镜头视频；在镜头组（ShotGroup）内支持自动抽取上一镜尾帧作为下一镜首帧，实现分镜间的丝滑连贯过渡；
- 🖼️ **多参考图+台词配音驱动演播 (Reference Image Generation)**：支持基于角色设定立绘、特定造型与场景环境多张参考图，联动角色克隆音色台词，调用 MiniMax H3 Ref2VA 模型驱动角色的生动演播、表情变化与动作呈现；
- 👥 **全流程人物视觉一致性维护 (Character Consistency)**：采用“角色身份层 (`ResCharacter`) + 造型层 (`ResCharacterLook`)”双层资产架构，配合跨集实体消歧流水线与 Character Blackboard 看板，确保角色在不同集数、多场景变换中面部外观、服装发型与设定的高保真视觉一致；
- ✍️ **构建符合 MiniMax H3 规范的专业提示词 (H3 Prompt Architecture)**：基于视听导演语言体系，将镜头剧本、角色动作与机位调度转译为标准的 MiniMax H3 双轨提示词（三段式/六段式结构），语义决策交给模型，后端负责结构校验。默认保留对白、环境声和必要音效，不加入背景音乐；用户明确要求配乐时可开启；
- 🧩 **Agent Skill 领域知识按需迭代与装载 (Iterative Agent Skills)**：内置标准化 Agent Skill 知识包（电影摄影构图、运镜机位、角色消歧与 H3 提示词编写规范），创作者可根据短剧题材与风格需要自由发布新版本、持续迭代优化正文规约，驱动大模型根据需要动态加载与进化。
- 🎞️ **官方 MiniMax H3 工作流与人脸修复**：接入 Comfy-Org 官方 FL2VA / Ref2VA 模板及 LightX2V Turbo4 / Turbo8 LoRA，支持文生、首帧、首尾帧、多参考图与音频生视频；可选择 H3 FaceRefine 二阶段人脸修复版本。
- 💬 **内置 FREYJA Agent**：支持对话中明确需求、创作小说、剧本拆解入库、资产整理、分镜制作规划与提示词准备；提供任务计划、进度、历史摘要、取消和恢复，以及可配置的模型、工具、Skills 和 Codex Pet 虚拟形象。
- 🔌 **MCP 服务与统一 AI 能力管理**：外部 Agent 可通过 MCP 操作授权项目、资产与分镜；内部 Agent 与 MCP 复用共享业务能力，工具名称、参数约束和公共规则由 MySQL 发布版本驱动，Redis 提供缓存与刷新通知。

## 💻 运行环境与硬件配置要求 (System Requirements)

在部署系统前，请根据您的使用场景评估机器配置：

| 配置项 | 最低要求 (Minimum) | 推荐配置 (Recommended) | 说明 |
| :--- | :--- | :--- | :--- |
| **GPU 独立显卡** | **16 GB 显存**（如 RTX 4080 16G / RTX 3090 24G 等） | **16 GB 以上显存**（如 RTX 4090 24G 或专业级工作站显卡） | FLUX.2 Klein 与 MiniMax H3 影视级视频 DiT 大权重加载与推理的硬性基准 |
| **宿主机内存 (RAM)** | **32 GB** | **64 GB 及以上** | 视频模型大权重加载与多阶段合流在 32G 下容易触碰虚拟内存交换上限，推荐 64G 获得最佳流水线流畅度 |
| **磁盘存储** | 预留 **100 GB** 以上（SSD 固态硬盘） | 预留 **200 GB** 以上（NVMe M.2 高速固态） | 存放基础模型权重（FLUX.2/H3/CLIP/VAE）、本地缓存与高清视频渲染资产 |
| **操作系统** | Windows 10/11 64位 或 Linux (Ubuntu 20.04/22.04 LTS) | Windows 11 / Linux (Ubuntu 22.04+ LTS) | 支持 NVIDIA 专有驱动、CUDA 环境与 Docker 编排 |
| **基础软件** | **Docker**（或 Docker Desktop） | **Docker Compose v2** | 用于 Freyja 核心服务集群的一键拉起与管理 |

> 💡 **轻量化/云端分工方案（强烈推荐）**：
> - **轻量主机 / 笔记本**：可以只运行 **Freyja 核心服务**（仅需 2~4GB 内存即可流畅运行短剧剧本大纲、资产管理与大模型拆解）；
> - **GPU 算力主机**：将配备 16G+ 显存的独显电脑或云端 GPU 服务器仅作为 **ComfyUI 外部算力机**，Freyja 通过局域网或公网远程调度调用！两者彻底解耦、互不挤占资源。

---

## 🚀 三步极速上手部署全流程 (Quickstart)

> 💡 **架构解耦设计**：本项目采用 **“Freyja 核心业务容器化 + 外部 ComfyUI 算力即插即用”** 的解耦架构。您只需按以下 **三步** 即可快速拉起完整短剧创作环境：
> - **第一步**：部署 Freyja 核心服务（通过 Docker / Docker Hub 一键启动，免去配置复杂开发环境）；
> - **第二步**：部署 ComfyUI 算力端（Windows 秋叶整合包 / Linux GPU 服务器，开启网络监听）；
> - **第三步**：下载模型权重并放置到指定目录（FLUX.2 生图与 MiniMax H3 影视级生视频模型）。

---

### 🌟 第一步：部署 Freyja 核心服务 (Docker / Docker Hub)

#### 1. 明确配置文件 (`.env`)
克隆本项目后，在根目录下复制配置模板：
```bash
cp .env.example .env
```
`.env` 配置文件核心字段详细说明如下：

| 配置项 | 默认值 | 作用与配置说明 |
| :--- | :--- | :--- |
| `IMAGE_PREFIX` | `astrag1/` | **Docker Hub 镜像命名空间**。默认直接拉取官方预编译镜像，免去本地漫长编译。 |
| `FREYJA_VERSION` | `1.0.0` | **镜像发布版本号**（支持 `1.0.0` 或 `latest`）。 |
| `COMFYUI_HTTP_URL` | `http://host.docker.internal:8188` | **外部 ComfyUI HTTP 地址**。<br>• 若 ComfyUI 运行在**本机**（Windows 秋叶包/本机 Linux）：保持默认 `http://host.docker.internal:8188`；<br>• 若运行在**独立局域网或云端 GPU 服务器**：修改为真实 IP，如 `http://192.168.1.100:8188`。 |
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
  > 几秒内即可下载完成并启动，无需在宿主机安装任何 JDK 26、Node.js、MySQL 等环境！

- **方案 B：本地一键自动化脚本或源码构建**：
  - Windows 用户：直接双击根目录下的 **[`docker-start.bat`](./docker-start.bat)**；
  - Linux / macOS 用户：执行 `chmod +x docker-start.sh && ./docker-start.sh`；
  - 或执行源码编译：`docker compose up -d --build`。

首次安装和已有数据卷升级都会先运行 `db-upgrade`，自动补建 MCP、内部 Agent 与共享能力相关表，成功后才启动后端。已有用户应同步更新仓库部署文件和 `.env` 中的镜像版本；升级步骤见下方「已有部署的自动数据库升级」及 [Docker 部署指南](docs/docker-deployment.md)。

#### 3. 核心服务访问入口
容器启动成功后，浏览器直接打开：

| 服务名称 | 访问地址 | 默认账号 / 密码 / 说明 |
| :--- | :--- | :--- |
| **🎬 Freyja 创作工作台** | **[http://localhost](http://localhost)** | 前端主界面（剧本拆解 / 镜头组 / 资产库） |
| **⚙️ 后端 RESTful 接口** | [http://localhost:8080](http://localhost:8080) | Spring Boot 业务端点 |
| **⚡ FastAPI 调度网关** | [http://localhost:8000/docs](http://localhost:8000/docs) | OpenAPI 交互文档与工作流映射看板 |
| **📦 MinIO 对象存储** | [http://localhost:9001](http://localhost:9001) | 用户名: `minioadmin` ｜ 密码: `minioadmin123` |
| **🗄️ MySQL 数据库** | `localhost:3306` | 用户名: `root` ｜ 密码: `root`（库名: `freyja`） |
| **⚡ Redis 缓存** | `localhost:6379` | 密码: `123456` |

---

### 🌟 第二步：部署 ComfyUI 外部算力机 (Windows 秋叶包 / Linux GPU)

ComfyUI 作为独立的图像与视频生成算力节点，运行在配备 NVIDIA 独立显卡（建议 $\ge 16\text{ GB}$ 显存）的机器上：

#### 1. 开启外部网络访问监听 (必须)
为了让 Docker 容器内的网关能向 ComfyUI 下发生成任务与读取进度，ComfyUI 必须允许跨机器/跨容器访问：
- **Windows 秋叶启动器用户**：
  打开秋叶启动器界面 -> 点击左侧【高级设置】 -> 勾选 **“允许局域网访问 (0.0.0.0)”** 以及 **“开启 CORS 跨域”**，默认监听端口为 `8188`；
- **官方便携版 / 命令行启动用户**：
  启动命令追加参数：
  ```bash
  python main.py --listen 0.0.0.0 --port 8188 --enable-cors-header
  ```

#### 2. 安装必要插件与显存优化加速包
- **安装节点管理器**：安装常用 `ComfyUI-Manager` 便于缺失节点一键排查；
- **使用原 16GB HiFi 工作流时安装显存优化插件**：
  前往 ModelScope 页面下载 **[ComfyUI-sol-attn-main.zip](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi/files)**，解压至 `ComfyUI/custom_nodes/ComfyUI-sol-attn`。该插件针对 MiniMax H3 注意力算子显存进行了专门优化，可显著降低显存峰值并加快视频生成速度。

官方 INT8 ConvRot / Turbo 工作流使用另一套原生节点与权重组合，依赖及下载步骤见下方「官方 MiniMax H3 工作流与人脸修复」。按选用的工作流准备 ComfyUI 环境。

#### 3. 验证网络通信
在运行 Freyja 的机器上，浏览器或命令行访问 `http://<ComfyUI所在IP>:8188`，若能正常打开 ComfyUI 界面即代表通信打通。

---

### 🌟 第三步：下载模型权重并放入对应目录 (Model Downloads)

将生图与影视级生视频所需的预训练模型权重下载并放置到 **ComfyUI 根目录下的对应文件夹** 中：

#### 1. 核心模型存放路径清单

下表为原 16GB HiFi 路线的基础权重。选用官方 INT8 ConvRot / Turbo 工作流时，请按「官方 MiniMax H3 工作流与人脸修复」中的对应部署文档准备独立的主模型、编码器、VAE 和 LoRA。

| 类别 | 推荐/默认文件名 | 存放路径 (ComfyUI目录下) | 官方/开源下载来源 | 用途说明 |
| :--- | :--- | :--- | :--- | :--- |
| **生图主干** | `flux-2-klein-9b.safetensors` | `models/unet/` | HuggingFace / 镜像站 | FLUX.2 Klein 9B 角色设定图、造型设计图生成 |
| **生图编码器** | `qwen_3_8b_fp8mixed.safetensors` | `models/clip/` | HuggingFace / 镜像站 | FLUX.2 文本提示词特征提取编码器 |
| **生图 VAE** | `flux2-vae.safetensors` | `models/vae/` | HuggingFace / 镜像站 | FLUX.2 图像潜空间编解码器 |
| **视频主干 (首尾帧)** | `minimax_h3_fl2va_dit_16g.safetensors` | `models/unet/` | [ModelScope 直达下载](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) | MiniMax H3 首尾帧驱动影视镜头生成 (带音效) |
| **视频主干 (参考图)** | `minimax_h3_ref2va_dit_16g.safetensors` | `models/unet/` | [ModelScope 直达下载](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) | MiniMax H3 多参考图+台词配音驱动角色演播生成 |
| **视频文本编码** | `qwen3vl_text_encoder.safetensors` | `models/clip/` | [ModelScope 直达下载](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) | MiniMax H3 视频生成提示词与多模态特征编码 |
| **视频视觉 VAE** | `minimax_h3_video_vae_fp16.safetensors` | `models/vae/` | [ModelScope 直达下载](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) | MiniMax H3 视频潜空间编解码器 |
| **伴生音频 VAE** | `minimax_h3_audio_vae_fp32.safetensors` | `models/vae/` | [ModelScope 直达下载](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) | MiniMax H3 原生伴生音频潜空间编解码器 |

#### 2. 进入 Web 工作台配置大模型 API Key
模型就绪后，打开创作工作台 **[http://localhost](http://localhost)**：
1. **配置大模型提供商**：进入【系统管理 -> AI 提供商管理】，录入你的大语言模型 API Key（用于剧本拆解 Planner / Worker，兼容 OpenAI 规范、DeepSeek、MiMo、硅基流动等各大服务商或本地 Ollama）；
2. **确认视听技能包 (Skills)**：进入【系统管理 -> AI 技能管理】，系统启动时会扫描并导入预置的 5 个 Skill 包（`character-disambiguation`、`character-sheet-design`、`camera-direction`、`cinematography`、`h3-prompt-writing`）；已有有效发布版本会保留。

---

## 🛠️ 功能模块

### 1. 四层剧作大纲与连续镜头组 (Drama Structure & ShotGroup)
- **四层大纲组织**：支持 `短剧 (Drama) -> 剧集 (Episode) -> 场次 (Scene) -> 分镜 (Shot)` 的树状结构维护。
- **连续镜头组 (ShotGroup)**：支持将连续镜头划归为镜头组；支持调用系统 FFmpeg 自动截取上一镜视频末帧，并将其直接指定为下一镜的首帧输入。
- **双模态生成支持**：支持配置「首尾帧驱动生视频 (First-Last Frame)」与「多参考图+配音驱动 (Reference-Audio)」两种生成模式。

### 2. 角色多造型管理与引用装配 (Character & Look Management)
- **身份层与造型层设计**：角色表（`res_character`）维护姓名、性别与基础特征；造型表（`res_character_look`）维护不同服装、发型与外观，支持指定默认造型与设定图。
- **提示词与资产关联**：在分镜配置与提示词组装时，自动关联当前镜头选定角色的外观提示词与参考图。
- **角色别名与实体消歧**：提供角色别名表（`res_character_alias`）与消歧记录，用于登记小说中同一角色的代词、称谓及别称映射。

### 3. 结构化导演规划与提示词转译 (DirectorPlan)
- **视听结构化字段**：提供景别（Shot Type）、运镜方式（Camera Movement）及 Camera Beats 运镜节拍时间轴的结构化配置。
- **提示词格式化转译**：支持将分镜的对白、动作与机位规划转译为适配 MiniMax H3 等模型的提示词结构。

### 4. 剧本并行分段拆解 (Parallel Script Decompose)
- **两阶段拆解流水线**：
  - **Planner 模型**：对长文本剧情进行结构化分段（Story Segments），并提取登场角色与场景列表；
  - **Worker 模型**：针对各剧情分段并发生成具体镜头的剧本内容、动作描述与对白；
- **服务端处理**：Java 服务端执行分镜序号重排、状态清洗去重与持久化落盘。

### 5. 服务容器化与外部算力调度 (Docker & ComfyUI Gateway)
- **容器化编排**：Web 前端、后端 API、MySQL、Redis、MinIO 及 FastAPI 网关支持通过 Docker Compose 一键启动。
- **外部算力直连**：ComfyUI 作为独立算力节点运行在 Windows 宿主机或 Linux GPU 服务器上，通过 HTTP / WebSocket 接收网关生成的任务与回传进度。

### 6. 分镜抽卡版本与视频后期处理 (Take History & Video Processing)
- **分镜视频抽卡历史 (Takes)**：支持单个分镜保留多次生成的视频历史记录，并支持在历史版本之间切换与重新选定。
- **视频超分与插帧**：接入 RealESRGAN x2 视频超分辨率与 RIFE 4.9 视频插帧（24 FPS 插帧至 48 FPS）工作流。

### 7. AI 技能知识资产管理 (Agent Skills)
- **标准 Agent Skill 规范**：支持导入标准 ZIP 格式的 Skill 资源包，支持版本控制、回滚、在线预览与动态装载。
- **内置视听与拆解规约**：预置 5 个 Skill，覆盖人物消歧、人物/造型设定图、电影摄影、运镜和 H3 提示词编写。Agent 配置中的「工具」和「Skills」分别选择执行能力与知识包，支持搜索、分页与跨页勾选。

### 8. 官方 MiniMax H3 工作流与人脸修复

在原 16GB HiFi 工作流基础上，增加基于 [Comfy-Org 官方模板](https://github.com/Comfy-Org/workflow_templates) 和 [官方适配权重](https://huggingface.co/Comfy-Org/MiniMax-H3) 的入口，并结合 [LightX2V Turbo LoRA](https://github.com/ModelTC/Minimax-H3-Turbo) 提供固定四步、八步采样。模型编码与 API 模板的实际绑定见 [默认算力节点配置](comfy_gateway/nodes/machine_default.yaml)。

| 网关模型编码 | 工作流能力 | 采样与说明 |
| --- | --- | --- |
| `minimax-h3-fl2va-turbo4` | 官方 FL2VA 模板适配，文生/首帧/首尾帧视频及原生音频 | 固定 4 步，使用 FL2V 四步 768p LoRA；[部署说明](docs/minimax-h3-fl2va-turbo4.md) |
| `minimax-h3-fl2va-turbo8` | 官方 FL2VA 模板适配，文生/首帧/首尾帧视频及原生音频 | 固定 8 步，默认使用标准版 544p LoRA；[部署说明](docs/minimax-h3-fl2va-turbo8.md) |
| `minimax-h3-ref` | 官方 Ref2VA 模板适配，多参考图与音频生视频 | 固定 4 步 |
| `minimax-h3-ref-turbo8` | 官方 Ref2VA 模板适配，多参考图与音频生视频 | 固定 8 步，使用 Ref2V 八步 768p LoRA；[部署说明](docs/minimax-h3-ref2va-turbo8.md) |
| `minimax-h3-face-refine` | 原 Ref2VA 生成与 H3 FaceRefine 人脸修复 | 二阶段处理，基础生成默认 20 步 |
| `minimax-h3-ref-turbo4-face-refine` | 官方 Ref2VA Turbo4 与 H3 FaceRefine 组合 | 生成和修复阶段均为 4 步 |
| `minimax-h3-ref-turbo8-face-refine` | 官方 Ref2VA Turbo8 与 H3 FaceRefine 组合 | 生成和修复阶段均为 8 步；[接入说明](docs/minimax-h3-ref2va-turbo8.md) |

**人脸修复接入方式**：在 AI 提供商与模型管理中注册对应网关模型编码，参考图模式选择带 FaceRefine 的模型。修复工作流将参考图片送入生成、修复两个阶段，最终合流修复后画面与首段原生音频；GPU 节点需安装 H3 FaceRefine 节点及其检测器（当前配置为 `face_yolov8m.pt`）。这是视频生成工作流内的二阶段修复，当前没有独立的人脸修复工作台。

UI 工作流位于 [`comfyUI_workflow/`](comfyUI_workflow/)，API 图位于 [`comfy_gateway/comfyUIApi/`](comfy_gateway/comfyUIApi/)。官方模板的人脸修复版是本项目组合扩展。不同 Turbo LoRA 对应固定采样步数，须下载匹配权重；通用 `steps` 参数不会覆盖 Turbo 入口的固定步数。尺寸、时长和显存需求按所选模型与节点环境核对。

### 9. 内置 Agent 与模型调用优化

- **对话驱动制作**：内置助手支持结构化需求确认、小说创作、剧本拆解及入库、文字资产整理、分镜模式与参考素材规划、提示词准备和交付检查；缺少输入或待补图片时会显示相应状态。任务计划和运行进度可在聊天及任务中心查看。
- **独立配置**：在 `/system/agent` 选择支持 Tool Calling 的 CHAT 模型，配置系统指令、工具、Skills、记忆、主模型与摘要模型预算、流式/完整回复、重试和执行时限。支持历史摘要、持久化检查点、取消、失败恢复及 Codex Pet ZIP 导入。
- **提供商能力继承**：在 AI 提供商页配置上下文上限、输出预算、工具调用与思考协议，具体模型可覆盖；任务创建时固定配置，模型能力由显式声明决定。
- **DeepSeek / MiMo 适配**：支持 DeepSeek Chat、MiMo Chat 等思考协议。DeepSeek 关闭思考时清理不适用的推理强度参数，开启时按协议处理采样参数；MiMo 支持思考开关，开启思考时清理不生效的温度与 Top P。请求兼容处理在 SDK 合并参数后执行。
- **长任务与摘要恢复**：主模型和摘要模型可以独立设置输出预算及思考方式；摘要截断或恢复耗尽时保留任务检查点，支持调整摘要配置后继续同一任务。模型轮数可选择不限制，工具次数、超时和预算策略仍可配置。

启用与接口说明见 [Agent 使用与 API](docs/agent-api.md)，最新能力目录和升级规则见 [统一能力平台](docs/mcp-mysql-redis-refactor-plan.md)。升级到统一能力平台后，内部 Agent 请新建会话，并在助手设置中核对所选工具。

提供商能力字段和思考协议示例见 [AI 提供商与模型 API](docs/ai-provider-api.md)。

### 10. MCP 服务与统一 AI 能力管理

| 管理入口 | 用途 |
| --- | --- |
| `/system/ai-capabilities` | 工具与入口绑定、名称/说明/参数约束、公共创作规则、外部指南、草稿发布和版本回滚 |
| `/system/agent` | 为内部助手选择已授权工具与 Skills，配置模型和运行策略 |
| `/system/mcp` | 外部 MCP 服务开关、Bearer 令牌、Origin、项目范围、写入权限与工具开关；工具和 Skills 独立展示 |

内部 Agent 直接调用共享 Java 能力层；外部 Agent 通过 **Streamable HTTP MCP** 访问同一批业务处理器。MySQL 保存能力身份、版本、绑定、规则与完整发布快照；Redis 用于缓存、刷新通知及实例状态，缓存故障时回源 MySQL。页面列表支持搜索、筛选和分页，公开工具名称从数据库绑定读取。

**发布流程**：编辑后「保存草稿」，再「发布版本」让新任务和后续 MCP 请求使用新配置，无需重启 Docker；运行中的内部任务固定规则与工具契约，停用工具会阻止后续调用。发布记录支持回滚。

**外部能力**：支持项目、剧集、场次、分镜及人物/造型/场景/道具/关键帧的查询和 CRUD，事务批量导入、参考图上传、视频版本读取/选择，以及已发布 Skills 的目录与正文读取。写入使用稳定 `requestId` 防止重复提交，修改前读取 `editFingerprint`，关键写入后读回核对。内部会话、AI 模型执行和系统设置接口保持内部授权。

在 `/system/mcp` 配置至少 24 字符的随机访问令牌，设置允许的项目及写入范围后开启服务。客户端连接示例：

```toml
[mcp_servers.freyja]
url = "http://localhost/api/mcp"
bearer_token_env_var = "FREYJA_MCP_TOKEN"
tool_timeout_sec = 300
```

将令牌放入客户端的 `FREYJA_MCP_TOKEN` 环境变量。远程客户端使用可访问的工作台地址；直接连接后端时路径为 `http://<host>:8080/mcp`。连接与业务 Schema 见 [MCP API](docs/mcp-api.md)，共享目录与发布机制见 [统一能力平台](docs/mcp-mysql-redis-refactor-plan.md)。

---

## 🧩 AI Skill 机制与内置技能规范

系统引入了标准 **Agent Skill** 机制，通过将专业影视与拆解规约模块化，在执行大模型调用时按需注入上下文或通过 Tool Calling 动态加载。

### 1. 系统默认内置的 5 个 Skill

系统默认自带 5 个 Skill 包，预置存放于项目根目录 **`skills/`** 文件夹中：

| Skill 标识 (name) | 预置文件路径 | 核心职责说明 | 关联调用链路 |
| :--- | :--- | :--- | :--- |
| **`character-disambiguation`** | `skills/character-disambiguation.zip` | 角色身份识别与实体消歧规约，用于判断登场角色身份、代词归一并锚定系统角色注册表 ID | Planner 剧本拆解阶段（强制依赖） |
| **`character-sheet-design`** | `skills/character-sheet-design.zip` | 人物与多造型设定图设计、三视图、头像与细节组织，以及按需求选择透明或纯色背景 | 角色/造型设定图创作 |
| **`camera-direction`** | `skills/camera-direction.zip` | 影视镜头运镜方式与机位轨迹规划规范（推拉摇移跟升降旋转） | 镜头编辑与视听导演规划 |
| **`cinematography`** | `skills/cinematography.zip` | 影视景别（特写/近景/中景/全景/远景）、构图与视听节奏设计规约 | 镜头景别规划与视听提示词衍生 |
| **`h3-prompt-writing`** | `skills/h3-prompt-writing.zip` | MiniMax H3 视频生成模型的双轨与三段式提示词撰写标准范式 | 分镜视频动态提示词生成 |

> ⚠️ **核心原则（可修改，切勿删除）**：
> - **保留业务依赖的 Skill 标识**：Planner、导演规划与 H3 提示词链路会按固定名称加载所需知识，删除相关 Skill 会影响对应流程；
> - **允许自由修改与调优**：在后台【系统管理 -> AI 技能管理 (`/system/skills`)】中保留 Skill 的 `name`，发布新版本、微调正文或补充参考资料；
> - **初始装载方式**：后端启动扫描内置 `skills/` 目录，自动导入尚缺失或无有效发布版本的技能；可在管理页核对结果，或手动导入 ZIP。已有有效版本不会被启动扫描覆盖。

---

### 2. 自定义扩展 Skill 指南

用户如果需要增加新的领域知识技能（如特定题材短剧叙事风格、特种光影规范、方言台词规约等）：

1. **依据 Agent Skill 规范创建**：打包为标准 ZIP 格式，根目录下必须包含 `SKILL.md` 文件（开头包含标准 YAML frontmatter 元数据 `name` 与 `description`），附属脚本或参考资料放入对应子目录；
2. **导入系统**：在 Web 工作台进入 **【系统管理 -> AI 技能管理 (`/system/skills`)】**，点击上传并导入您制作的 ZIP 技能包；
3. **驱动 AI 使用该技能（二选一）**：
   - **方式 A（修改对应系统提示词）**：在 **【系统管理 -> 系统参数配置 (`/system/config`)】** 中，修改对应业务阶段的 System Prompt（例如 `ai.prompt.planner_system` 或 `ai.prompt.shot_worker_system`），在提示词中加入指令提示 AI 使用该 Skill，例如：
     ```text
     在执行镜头剧本拆解时，你必须加载并严格遵循 [your-custom-skill] 技能规约。
     ```
   - **方式 B（在支持指定 Skill 的地方显式指定）**：在系统支持选择 Skill 的工作台区域（如剧本拆解弹窗的技能选择下拉列表）直接勾选并指定这些 Skill 即可。

---

## 🤖 系统模型清单 (Model Specification)

系统运行涉及**大语言模型（剧本拆解与规划）**与**图像/视频生成模型（ComfyUI 算力端调度）**两大部分。

### 1. 文本与语音模型 (LLM & TTS)
后端基于 Spring AI 提供商抽象，支持任何符合 OpenAI 规范的 API 或本地 Ollama 实例。当前系统测试验证所使用的参考模型如下：

| 模块分类 | 职责说明 | 测试使用模型 (仅供参考) |
| :--- | :--- | :--- |
| **Planner AI** | 负责全章小说宏观理解、剧情节奏分段（Story Segments）与核心角色/场景资产提炼 | `mimo-v2.6-flash` |
| **Worker AI** | 负责各分段剧情并发拆解，生成单镜头的剧本对白、动作与机位视听描述 | `mimo-v2.6-flash` |
| **TTS 配音模型** | 负责角色专属音色克隆与分镜台词语音合成 | `mimo-v2.5-tts-voicedesign` |

### 2. 图像与视频生成模型 (ComfyUI 端)
ComfyUI 算力节点预置工作流所依赖的专用模型权重清单（存放于 `ComfyUI/models/` 对应子目录）：

| 模型名称 | 文件类型 | 推荐/默认文件名 | 模型作用与调用场景 | 来源 / 存放目录 |
| :--- | :--- | :--- | :--- | :--- |
| **FLUX.2 Klein 9B** | UNET (DiT) | `flux-2-klein-9b.safetensors` | 角色设定参考图、造型设计图、分镜首帧/关键帧生成 | `models/unet/` |
| **Qwen3 8B CLIP** | CLIP | `qwen_3_8b_fp8mixed.safetensors` | FLUX.2 生图提示词特征编码器 | `models/clip/` |
| **FLUX.2 VAE** | VAE | `flux2-vae.safetensors` | FLUX.2 图像潜空间编解码 | `models/vae/` |
| **MiniMax H3 FL2VA** | UNET (DiT) | `minimax_h3_fl2va_dit_16g.safetensors` | 首尾帧驱动影视镜头生成 (带音效) | [ModelScope](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) -> `models/unet/` |
| **MiniMax H3 Ref2VA**| UNET (DiT) | `minimax_h3_ref2va_dit_16g.safetensors` | 多参考图+台词配音驱动角色演播生成 | [ModelScope](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) -> `models/unet/` |
| **Qwen3-VL Encoder** | CLIP | `qwen3vl_text_encoder.safetensors` | MiniMax H3 视频生成提示词与多模态特征编码 | [ModelScope](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) -> `models/clip/` |
| **MiniMax Video VAE** | VAE | `minimax_h3_video_vae_fp16.safetensors` | MiniMax H3 视频潜空间编解码器 | [ModelScope](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) -> `models/vae/` |
| **MiniMax Audio VAE** | VAE | `minimax_h3_audio_vae_fp32.safetensors` | MiniMax H3 原生伴生音频潜空间编解码器 | [ModelScope](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) -> `models/vae/` |

### 3. 视频后处理增强模型
内置在视频工坊中，用于镜头渲染完成后的后处理增强：

| 模型名称 | 默认权重文件名 | 处理能力 | 存放目录 |
| :--- | :--- | :--- | :--- |
| **RIFE 4.9** | `rife49.pth` | 视频智能光流插帧（将生成的 24 FPS 镜头平滑倍增至 48 FPS 影视帧率） | `custom_nodes/ComfyUI-Frame-Interpolation/ckpts/rife/` |
| **RealESRGAN x2plus**| `RealESRGAN_x2plus.pth` | 视频 2 倍超分辨率无损重建（提升镜头清晰度与纹理细节） | `models/upscale_models/` |

---

## 🏛️ 系统架构拓扑

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        Vue 3 前端 (Vite + Element Plus)                │
│  ├─ 剧作大纲卡片流工作台 (剧本拆解 / 镜头组 / 双轨 Prompt 衍生)           │
│  ├─ 资产工坊 (角色身份与造型库 / 专属音色设计 / 场景与道具资产)          │
│  └─ 视频后期中心 (超分辨率放大 / RIFE 平滑插帧 / 抽卡版本追溯)          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ HTTP RESTful / WebSocket
┌───────────────────────────────────▼────────────────────────────────────┐
│                    Spring Boot 4 后端业务核心 (JDK 26)                  │
│                                                                        │
│  ┌──────────────────────┐  ┌────────────────────────────────────────┐  │
│  │   Spring AI 智能引擎 │  │         ComfyUI 异步调度网关           │  │
│  │  - Planner / Worker  │  │  - FastAPI 兼容层与工作流模板引擎       │  │
│  │  - DirectorPlan 规划 │  │  - 状态追踪与 WebSocket 进度广播        │  │
│  └──────────────────────┘  └────────────────────────────────────────┘  │
│  ┌──────────────────────┐  ┌────────────────────────────────────────┐  │
│  │   实体看板与资产决策 │  │             双轨资产归档服务           │  │
│  │  - ResCharacter+Look │  │  - MinIO Client (云端对象存储与预览)   │  │
│  │  - 动态数据字典/配置 │  │  - 本地剪辑目录同步与 FFmpeg 尾帧抽取  │  │
│  └──────────────────────┘  └────────────────────────────────────────┘  │
└──────────────┬───────────────────┬───────────────────┬─────────────────┘
               │                   │                   │
     ┌─────────▼───────────┐ ┌─────▼───────┐ ┌─────────▼───────────┐
     │  LLM (MiMo Flash /  │ │    MySQL    │ │   外部 ComfyUI 算力机   │
     │   OpenAI / Ollama)  │ │   + Redis   │ │ (Windows 秋叶包 /       │
     │     (Spring AI)     │ │   + MinIO   │ │  Linux GPU 服务器)      │
     └─────────────────────┘ └─────────────┘ └─────────────────────┘
```

---

## ⚙️ Docker 进阶配置与部署指南

### 1. 常用运维管理命令

可在项目根目录下通过命令行或提供的脚本管理容器服务集群：

```bash
# 查看所有容器运行状态与健康检查
docker compose ps

# 查看后端实时日志
docker compose logs -f backend

# 查看网关调度日志
docker compose logs -f comfy-gateway

# 安全停止全部服务 (保留数据卷与资产)
docker compose stop   # 或 Windows 双击 docker-stop.bat / Linux 执行 ./docker-stop.sh

# 重新拉起已有容器
docker compose start

# 彻底清理并重置系统与数据库 (⚠️ 会删除物理数据卷，谨慎使用)
docker compose down -v
```

### 2. 生产环境安全与密钥重置

> [!WARNING]
> **在 Docker 部署环境下，AI 提供商的 Base URL 切勿直接填写 `127.0.0.1` 或 `localhost`！**
>
> 1. **容器网络隔离原理**：
>    在 Docker 容器化环境中，每个容器拥有完全独立的网络命名空间（Network Namespace）。`127.0.0.1` 永远指向发起请求的容器本身。若在系统【系统管理 -> AI 提供商配置】中将 FastApi 网关的 Base URL 填为 `http://127.0.0.1:8000/v1`，后端容器（`freyja-backend`）会在其自身容器内部寻找 8000 端口，从而导致 `Connection Refused`（连接拒绝）或网络请求超时。
>
> 2. **推荐的 Base URL 配置方式**：
>    - **Docker 容器间直连（官方最佳实践，默认推荐）**：
>      ```text
>      http://comfy-gateway:8000/v1
>      ```
>      在同一 Docker Compose 网络中，Docker 内置 DNS 服务会自动解析服务名 `comfy-gateway`，通信性能最高且不受宿主机端口变动影响。
>    - **全场景兼容网关模式（同时支持本地 IDEA 与 Docker 容器）**：
>      ```text
>      http://host.docker.internal:8000/v1
>      ```
>      借助 Docker 为容器访问宿主机设立的网关映射，无论后端运行在 Docker 容器还是本地 IDEA 均能成功通信。
>
> 3. **系统内置智能防呆容错机制**：
>    后端 `AiImageApiService` 服务现已内置环境自适应感知逻辑：若检测到系统当前运行于 Docker 容器中且检测到用户误配了 `127.0.0.1:8000` 或 `localhost:8000`，系统将自动智能重定向至 `http://comfy-gateway:8000` 进行通信，防止任务意外中断。但生产环境仍建议在控制台规范录入。

### 3. Docker Hub 镜像中心与 GitHub Actions 自动云构建

本项目已接入 **GitHub Actions CI/CD 云端自动化构建与发布流水线**，构建产物托管在官方 Docker Hub 仓库：

| 组件名称 | Docker Hub 镜像地址 | 默认版本 Tag |
| :--- | :--- | :--- |
| **前端 Web 工作台** | `astrag1/freyja-frontend` | `1.0.0` / `latest` |
| **后端核心服务** | `astrag1/freyja-backend` | `1.0.0` / `latest` |
| **ComfyUI 调度网关** | `astrag1/freyja-comfy-gateway` | `1.0.0` / `latest` |

- **多主机/生产服务器极速更新**：
  先同步仓库部署文件，再通过 `.env` 指定已发布的镜像版本，执行 `docker compose pull` 和 `docker compose up -d`。Compose 会先完成 MCP / Agent 数据库增量升级，再启动后端，无需在生产端安装编译环境；
- **自定义发布流水线**：
  开发者 Fork 本仓库后，只需在 GitHub 仓库 `Settings -> Secrets and variables -> Actions` 中配置个人 `DOCKERHUB_USERNAME` 与 `DOCKERHUB_TOKEN`，每次向 `master` 分支推送代码或手动点击 `Run workflow` 即可一键自动化多阶段交叉构建并推送到自己的 Docker Hub 命名空间。

> 详细配置与运维说明请参阅 [Docker 容器化部署指南](docs/docker-deployment.md)。

### 4. 已有部署的自动数据库升级

更新仓库部署文件（可在项目根目录执行 `git pull`），确保 `docker-compose.yml`、`docker/mysql/migrations/` 和 `src/main/resources/sql/` 与目标版本一致，再将 `.env` 中的 `FREYJA_VERSION` 设置为要部署的已发布镜像版本。只拉取镜像不会更新宿主机上的 Compose、迁移脚本或 SQL 文件。

```bash
docker compose pull
docker compose up -d
```

本地源码构建也可使用 `docker compose up -d --build` 或现有一键启动脚本，两种方式都会自动运行升级服务。

- **自动执行范围**：MySQL 健康后，`db-upgrade` 容器运行 [自动升级脚本](docker/mysql/migrations/auto-upgrade-ai-infrastructure.sh)，补建 Agent 基础表、制作准备表、MCP 请求记录表及共享能力表，并将关键图图片 URL 放宽为可空。其他历史业务字段升级仍按 [部署指南](docs/docker-deployment.md) 的对应迁移说明执行。
- **保留数据与配置**：保留已有数据卷，不重建业务库，不覆盖项目、媒体、模型、Agent 设置、MCP 令牌或已发布能力版本。成功步骤写入 `freyja_schema_migration`，重复部署自动跳过；`db-upgrade` 显示 `Exited (0)` 属于正常完成状态。
- **失败与重试**：SQL 执行失败时，新后端不会启动。使用 `docker compose logs db-upgrade` 查看错误，检查数据库连接及 CREATE / ALTER、迁移记录表读写权限，修复后执行以下命令重试。

```bash
docker compose up -d --force-recreate db-upgrade backend frontend
```

升级时保留数据卷，不执行 `docker compose down -v` 或完整初始化 SQL；不要使用 `--no-deps` 跳过迁移依赖。`docker compose restart` 只重启旧容器，不能替代上述升级命令。数据库初始化完成后，MCP 和内部 Agent 仍需在管理页按需配置令牌、模型、权限及启用开关。

---

## 🎨 配套 ComfyUI 算力机配置与工作流机制

### 1. 硬件配置要求与视频模型说明

硬件配置要求详情请参阅文档开篇的 [💻 运行环境与硬件配置要求](#-运行环境与硬件配置要求-system-requirements)。

- **16GB HiFi 工作流**：配套采用 ModelScope 的 **[MiniMax H3 混合高精度量化版](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)**，相应显存优化插件按该工作流要求安装；
- **官方模板与 Turbo 工作流**：使用 Comfy-Org INT8 ConvRot 权重与匹配的 Turbo LoRA，具体依赖、模型下载和人脸修复接入见上方「官方 MiniMax H3 工作流与人脸修复」及对应部署文档。

### 2. 工作流机制说明
- **工作流适配机制**：首尾帧与参考素材注入依赖已注册模板的节点 ID、映射与处理器；内置模板已覆盖 FLUX.2、16GB HiFi、官方 H3 Turbo4/Turbo8、FaceRefine、RIFE 和 RealESRGAN。第三方工作流需先完成网关适配再调用；
- **开发者接入新工作流逻辑**：
  若需扩展新工作流，需遵循三步工程链路：
  1. ComfyUI 开启开发者模式，导出 **Save (API Format)** 格式的纯参数 JSON 模板并放入 `comfy_gateway/comfyUIApi/`；
  2. 在 `comfy_gateway/nodes/*.yaml` 中配置节点参数映射关系；
  3. 在 `comfy_gateway/workflow_engine.py` 中复用或增加对应的 Handler 处理逻辑，并验证参数注入与输出节点。

### 3. ComfyUI 启动与连接配置
1. **允许外部网络连接**：
   - **Windows 秋叶启动器**：高级设置勾选 **“允许局域网访问 (0.0.0.0)”** 与 **“开启 CORS 跨域”**；
   - **命令行启动**：添加参数 `--listen 0.0.0.0 --port 8188 --enable-cors-header`；
2. **连接配置**：
   - 若 ComfyUI 运行在当前 Docker 宿主机电脑（Windows/Linux）：`.env` 保持默认 `COMFYUI_HTTP_URL=http://host.docker.internal:8188`；
   - 若 ComfyUI 运行在独立 GPU 服务器：修改 `.env` 中的 `COMFYUI_HTTP_URL=http://<GPU主机IP>:8188`。

> 模型放置规范与必装插件清单详见 [ComfyUI 工作流配置指南](comfyUI_workflow/README.md)。

---

## 🛠️ 本地开发环境

### 1. 技术栈
- **后端**：Java 26 + Maven 3.9+ + Spring Boot 4.1.1 + MyBatis-Plus 3.5.17 + Spring AI 2.0.1
- **MCP 服务**：实际引入 `spring-ai-starter-mcp-server-webmvc`（由 Spring AI 2.0.1 BOM 管理），提供默认关闭的 Streamable HTTP `/mcp` 业务接口。72 个工具覆盖 10 类对象单条 CRUD、独立批量修改、最多100项跨对象创建/逻辑删除、单张/批量图片上传及3个只读 Skill 知识工具，手写 SQL 放 MyBatis XML。`batch_upload_asset_images` 每批1–20张、单张10 MiB、整批20 MiB，返回有序结果与 URL 映射；`refImages` 使用明确字段并兼容 `CHARACTER_LOOK` 造型来源。外部 Agent 应补齐分镜规格与资产绑定，景别/运镜可选AUTO；先读取已启用发布版本的 `h3-prompt-writing` 及参考文件，再填写首尾帧三段式 `videoPrompt` 或参考模式六段式 `prompt`。导演计划仍可省略，不发布内部 Agent、模型推理和 AI 提示词生成接口。管理页 `/system/mcp` 支持业务 CRUD 能力矩阵、整体/单工具热开关、令牌和项目授权；参数错误返回简短结构化原因。客户端工具超时建议300秒。必填规则见 [MCP 字段核对](docs/mcp-fields.md)，启用和连接见 [MCP 使用说明](docs/mcp-api.md)，交付与验证见 [实现清单](docs/mcp-implementation.md)。
- **MCP 数据库测试**：实际引入 H2（仅 `test` 作用域），在内存数据库中验证 MyBatis 查询、分页和原有业务 CRUD 的事务；运行时数据库保持 MySQL。
- **前端**：Node.js 22+ + Vite 6 + Vue 3.5 + TypeScript + Element Plus + Tailwind CSS
- **网关**：Python 3.10+ + FastAPI + Uvicorn

### 2. 本地调试启动
- **后端**：
  复制 `src/main/resources/application-local.yaml.example` 为 `application-local.yaml`，填入本地数据库连接，在 IDEA 中指定 Profile 为 `local` 即可运行（该文件已加入 `.gitignore`）。
- **前端**：
  ```bash
  cd front
  npm install
  npm run dev
  ```
- **网关**：
  ```powershell
  cd comfy_gateway
  .\start.ps1   # Windows
  # 或 ./start.sh # Linux / macOS
  ```

---

## 📂 项目结构

```text
freyja/
├── comfy_gateway/          # FastAPI ComfyUI 调度网关
│   ├── comfyUIApi/         # 工作流 JSON API 模板映射
│   ├── nodes/              # 算力节点配置目录
│   └── main.py             # 网关服务入口
├── comfyUI_workflow/       # 配套 ComfyUI 工作流文件与说明
├── docker/                 # MySQL 初始化 DDL
├── docs/                   # 接口文档与部署规范
├── front/                  # Vue 3 前端源码
├── image/                  # 项目视觉海报与角色立绘
│   ├── freyja1.png         # Freyja 角色立绘 (吉祥物)
│   └── freyja2.png         # FREYJA AI DIGITAL STUDIO 全景概念海报
├── skills/                 # 预置 AI 技能知识包 (内置 4 个核心 Skill ZIP 包)
│   ├── camera-direction.zip
│   ├── character-disambiguation.zip
│   ├── cinematography.zip
│   └── h3-prompt-writing.zip
├── src/                    # Spring Boot 4 后端源码
├── docker-compose.yml      # Docker 编排文件
├── Dockerfile              # 后端 Dockerfile
└── LICENSE                 # Apache 2.0 许可证
```

---

## 💬 现状、已知问题与共建呼吁 (Current Status & Contribution)

1. **关于 Bug 反馈与维护说明**：
   - 如果在使用过程中遇到系统 Bug 或异常，欢迎在 GitHub 提交 [Issues](https://github.com/your-username/freyja/issues)；
   - **注意**：由于作者平时工作繁忙、个人可支配的开发时间非常有限，**无法保证 Issue 的响应速度与修复时效**，还请多加包涵与理解；更欢迎社区开发者直接提交 Pull Request 协助修复。

2. **当前系统面临的核心局限**：
   - **AI 分镜效果不够稳定**：当前内置的技能规约专业度仍有欠缺，大模型在自动拆解小说剧情时生成的机位、景别与视听节奏时好时坏，实际创作中**往往需要人工进行介入调整与二次修改**；
   - **提示词衍生质量有待提升**：负责提示词生成的 AI 有时转译出的镜头描述不够地道或不够贴合画面细节，同样需要创作者手动润色。

3. **求影视/视听领域大佬参与优化两个核心 Skill**：
   - 系统内置的 **`cinematography`**（电影摄影构图与景别设计规约）和 **`camera-direction`**（镜头运镜与机位轨迹规约）是决定全流程分镜专业感的核心知识基石；
   - 非常欢迎熟悉专业影视摄影、分镜设计的大佬针对 `skills/cinematography.zip` 与 `skills/camera-direction.zip` 中的规则文档（`SKILL.md`）进行专业化升级、补充高质量范例，欢迎提交 PR 共同打磨！

![Freyja Mascot](image/freyja1.png)

---

## 📄 开源许可证

本项目基于 [Apache 2.0 License](LICENSE) 协议开源。
