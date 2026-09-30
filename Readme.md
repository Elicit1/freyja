<h1 align="center">🎬 FREYJA AI DIGITAL STUDIO (MiniMax H3)</h1>

<p align="center">
  <strong>基于 Spring Boot 4 + Vue 3 + MiniMax H3 影视级模型的 AI 短剧分镜制作与 ComfyUI 调度平台</strong>
  <br />
  四层剧作大纲 ｜ 角色造型资产 ｜ 结构化导演规划 ｜ 并行剧本拆解 ｜ ComfyUI 调度网关 ｜ 视频超分与补帧
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

**Freyja** 是一个面向 AI 短剧分镜制作的开源管理与调度系统。

系统围绕短剧制作流程，提供“**四层剧作大纲管理 + 角色/场景资产库 + 大模型剧本自动拆解 + ComfyUI 异步任务调度 + 媒体资产本地归档**”功能。

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
- **安装 H3 显存优化加速插件 (强烈推荐)**：
  前往 ModelScope 页面下载 **[ComfyUI-sol-attn-main.zip](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi/files)**，解压至 `ComfyUI/custom_nodes/ComfyUI-sol-attn`。该插件针对 MiniMax H3 注意力算子显存进行了专门优化，可显著降低显存峰值并加快视频生成速度。

#### 3. 验证网络通信
在运行 Freyja 的机器上，浏览器或命令行访问 `http://<ComfyUI所在IP>:8188`，若能正常打开 ComfyUI 界面即代表通信打通。

---

### 🌟 第三步：下载模型权重并放入对应目录 (Model Downloads)

将生图与影视级生视频所需的预训练模型权重下载并放置到 **ComfyUI 根目录下的对应文件夹** 中：

#### 1. 核心模型存放路径清单

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
2. **确认视听技能包 (Skills)**：进入【系统管理 -> AI 技能管理】，系统已自动扫描并装载了预置的 4 个视听/导演 Skill 包（`character-disambiguation`、`camera-direction`、`cinematography`、`h3-prompt-writing`），开箱即用！

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
- **内置视听与拆解规约**：预置 4 个核心视听与业务规则 Skill，为剧本拆解与提示词衍生大模型提供领域专业知识与指令规约。

---

## 🧩 AI Skill 机制与内置技能规范

系统引入了标准 **Agent Skill** 机制，通过将专业影视与拆解规约模块化，在执行大模型调用时按需注入上下文或通过 Tool Calling 动态加载。

### 1. 系统默认内置的 4 个核心 Skill

系统默认自带 4 个核心 Skill 包，预置存放于项目根目录 **`skills/`** 文件夹中：

| Skill 标识 (name) | 预置文件路径 | 核心职责说明 | 关联调用链路 |
| :--- | :--- | :--- | :--- |
| **`character-disambiguation`** | `skills/character-disambiguation.zip` | 角色身份识别与实体消歧规约，用于判断登场角色身份、代词归一并锚定系统角色注册表 ID | Planner 剧本拆解阶段（强制依赖） |
| **`camera-direction`** | `skills/camera-direction.zip` | 影视镜头运镜方式与机位轨迹规划规范（推拉摇移跟升降旋转） | 镜头编辑与视听导演规划 |
| **`cinematography`** | `skills/cinematography.zip` | 影视景别（特写/近景/中景/全景/远景）、构图与视听节奏设计规约 | 镜头景别规划与视听提示词衍生 |
| **`h3-prompt-writing`** | `skills/h3-prompt-writing.zip` | MiniMax H3 视频生成模型的双轨与三段式提示词撰写标准范式 | 分镜视频动态提示词生成 |

> ⚠️ **核心原则（可修改，切勿删除）**：
> - **切勿移除这 4 个默认 Skill**：系统的核心业务链路（如 Planner 剧本拆解、分镜双轨提示词衍生、导演规划）在后端逻辑中对这 4 个 Skill 标识有显式的依赖校验与强制加载要求，删除会导致业务流程异常；
> - **允许自由修改与调优**：创作者完全可以基于这 4 个 Skill 进行业务调整。在后台【系统管理 -> AI 技能管理 (`/system/skills`)】中查看其内容细节，在保留原 Skill 标识（name）的前提下发布新版本、微调其正文规约或补充参考资料；
> - **初始装载方式**：系统部署初始化后，进入后台【系统管理 -> AI 技能管理 (`/system/skills`)】，点击“导入技能”，分别上传项目根目录 `skills/` 下的 4 个 ZIP 文件即可完成装载。

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
  在任意部署服务器上，只需通过 `.env` 指定镜像版本，执行 `docker compose pull && docker compose up -d` 即可在几秒内拉取最新镜像平滑热更，彻底免除在生产端安装编译环境；
- **自定义发布流水线**：
  开发者 Fork 本仓库后，只需在 GitHub 仓库 `Settings -> Secrets and variables -> Actions` 中配置个人 `DOCKERHUB_USERNAME` 与 `DOCKERHUB_TOKEN`，每次向 `master` 分支推送代码或手动点击 `Run workflow` 即可一键自动化多阶段交叉构建并推送到自己的 Docker Hub 命名空间。

> 详细配置与运维说明请参阅 [Docker 容器化部署指南](docs/docker-deployment.md)。

---

## 🎨 配套 ComfyUI 算力机配置与工作流机制

### 1. 硬件配置要求与视频模型

| 配置项 | 最低要求 (Minimum) | 推荐配置 (Recommended) | 说明 |
| :--- | :--- | :--- | :--- |
| **GPU 独立显卡** | **16 GB 显存**（如 RTX 4080 16G / RTX 3090 24G 等） | **16 GB 以上显存**（如 RTX 4090 24G 或专业级工作站显卡） | FLUX.2 Klein 与 MiniMax H3 视频 DiT 大权重加载与推理的硬性基准 |
| **宿主机内存 (RAM)** | **32 GB** | **64 GB 及以上** | 视频模型大权重加载与多阶段合流在 32G 下容易触碰虚拟内存交换上限，推荐 64G 获得最佳流水线流畅度 |
| **磁盘存储** | 预留 **100 GB** 以上（SSD 固态硬盘） | 预留 **200 GB** 以上（NVMe M.2 高速固态） | 存放基础模型权重（FLUX.2/H3/CLIP/VAE）、缓存与高清视频渲染资产 |
| **操作系统** | Windows 10/11 64位 或 Linux (Ubuntu 20.04/22.04 LTS) | Windows 11 / Linux (Ubuntu 22.04+ LTS) | 支持 NVIDIA 专有驱动、CUDA 环境与 Docker 编排 |

- **视频生成模型**：系统视频生成工作流配套采用 ModelScope 开源的 **[MiniMax H3 混合高精度量化 (16G显存优化版)](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)**；
- **推理加速包**：强烈建议配合下载 ModelScope 文件列表中的 **[ComfyUI-sol-attn-main.zip](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi/files)** 加速插件（解压至 `ComfyUI/custom_nodes/ComfyUI-sol-attn`），专用于优化注意力算子显存占用并提升生成速度。

### 2. 工作流机制说明
- **工作流固定机制**：系统的提示词组装流水线、首尾帧与参考图槽位注入逻辑与内置工作流的特定节点 ID 深度耦合。**系统不支持自动接入或直接运行任意外部第三方的 ComfyUI 工作流，开箱仅支持系统内置的 5 套预置工作流**；
- **开发者接入新工作流逻辑**：
  若需扩展新工作流，需遵循三步工程链路：
  1. ComfyUI 开启开发者模式，导出 **Save (API Format)** 格式的纯参数 JSON 模板并放入 `comfy_gateway/comfyUIApi/`；
  2. 在 `comfy_gateway/nodes/*.yaml` 中配置节点参数映射关系；
  3. 在 `comfy_gateway/adapters.py` 中编写对应的 Handler 数据处理逻辑（**可借助 Codex、Gemini、Claude Code、Cursor、OpenCode 等 AI 工具，将 API JSON 与需求发给 AI 快速生成适配代码**）。

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
