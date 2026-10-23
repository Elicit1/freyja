<h1 align="center">🎬 FREYJA AI 短剧创作管理系统</h1>

<p align="center">
  <strong>专为短剧创作者设计的 AI 视频制作管理平台</strong>
  <br />
  内置 AI 编剧助手 ｜ 支持 Qoder / Codex 等外部 AI 接入与一键配图 ｜ 官方视频模型 ｜ 4/8步快速出片 ｜ 人脸修复防崩脸
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3.5-42b883.svg" alt="Vue 3">
  <img src="https://img.shields.io/badge/MCP-Streamable%20HTTP-blueviolet.svg" alt="MCP">
  <img src="https://img.shields.io/badge/External%20AI-Qoder%20%2F%20Codex-orange.svg" alt="Qoder / Codex">
  <img src="https://img.shields.io/badge/Video%20Model-MiniMax%20H3-critical.svg" alt="MiniMax H3">
  <img src="https://img.shields.io/badge/Speed-4%2F8%20Step%20Turbo-yellow.svg" alt="Turbo 4/8 Step">
  <img src="https://img.shields.io/badge/Face%20Fix-Supported-success.svg" alt="Face Refinement">
  <img src="https://img.shields.io/badge/Docker-One--Click-2496ed.svg" alt="Docker">
</p>

<br />

![FREYJA AI DIGITAL STUDIO](./image/freyja2.png)

<br />

---

## 📖 什么是 Freyja？

**Freyja** 是一个帮助普通创作者制作 AI 短剧的一站式管理平台。

在制作 AI 短剧时，大家通常会遇到这些麻烦：
- **小说太长**：一段段手动写提示词、建分镜，繁琐又容易出错；
- **人物总变脸**：上一秒是瓜子脸，下一秒变成圆脸，衣服也换得对不上；
- **视频容易崩**：AI 生成的视频人物经常面部畸变、眼睛走样；
- **渲染太慢**：每段视频都要等几分钟，试错成本高。

**Freyja 把这些繁琐环节全部串联打通**：从小说自动拆成剧本分镜、固定角色长相与换装、一键连接 **Qoder / Codex** 等 AI 客户端直接画图上传，再到支持 MiniMax H3 官方视频模型、4步/8步极速生成与二阶段人脸修复，让普通人也能高效、稳定地做出高质量短剧！

---

## 🌟 核心功能一览

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        FREYJA AI 短剧创作工作台                        │
├───────────────────────────────────┬────────────────────────────────────┤
│           🤖 网页内置 AI 编剧助手          │         🔌 连接外部 AI (Qoder/Codex/Cursor)│
│  - 打开网页直接聊天，小说一键拆分成镜头  │  - 推荐连接自带生图能力的 AI 客户端   │
│  - 自动提炼角色与场景，准备分镜制作资料  │  - 外部 AI 自动画图、自动上传并关联资产 │
│  - 支持 DeepSeek / MiMo 思考协议         │  - 72+ 个工具，直接帮你管大纲与提示词 │
├───────────────────────────────────┴────────────────────────────────────┤
│                         🎥 高清视频生成与画面优化引擎                  │
│  ┌───────────────────────┬───────────────────────┬──────────────────┐  │
│  │   MiniMax H3 官方模型 │    ⚡ 4步/8步快速出片 │   🎭 人脸修复防崩脸 │  │
│  │ • 首尾两张图生成连贯动作 │ • 4步极速出片，快速预览│ • 自动识别人脸并追踪 │  │
│  │ • 多张参考图+台词演播  │ • 8步高质出片，质感均衡│ • 局部精细重绘贴回   │  │
│  │ • 自带原声伴生音频     │ • 大幅降低生成等待时间 │ • 告别面部扭曲与变形 │  │
│  └───────────────────────┴───────────────────────┴──────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 🔌 强烈推荐：接入 Codex或国内可以生图Agent平台

Freyja 支持标准的 **MCP（Model Context Protocol）协议**。如果你平时使用支持该协议且**具备生图能力的 AI 客户端**（如 **Qoder**、**Codex**、**Cursor**、**Claude Desktop** 等），可以把 Freyja 连接进去，让外部 AI 变成你的全能短剧制作助手。

> 💡 **核心优势：外部 AI 自动生成图片并直接存入 Freyja**  
> 像 **Codex** 这类工具可以写文戏、拆剧本，还自带 AI 绘图能力。连接后，它们可以一边构思角色，一边调用生图模型画出角色立绘或场景图，并通过 Freyja 的上传工具直接存入系统并绑定到对应角色或分镜中，创作者无需手动下载再上传！

### 快速连接步骤

#### 第一步：在 Freyja 获取连接令牌
1. 在网页端进入 **【系统管理 → MCP 服务 (`/system/mcp`)】**；
2. 点击生成一个随机访问密钥（Bearer Token）；
3. 勾选允许访问的项目与工具权限，点击**开启 MCP 服务**。

#### 第二步：在 Codex 中添加配置

**TOML 格式客户端：**
```toml
[mcp_servers.freyja]
url = "http://localhost/api/mcp"
bearer_token_env_var = "FREYJA_MCP_TOKEN"
tool_timeout_sec = 300
```
*在环境变量中设置 `FREYJA_MCP_TOKEN` 为后台复制的访问密钥。*

**JSON 格式客户端（如 Qoder、Codex、Cursor、Windsurf 等）：**
```json
{
  "mcpServers": {
    "freyja": {
      "url": "http://localhost/api/mcp",
      "headers": {
        "Authorization": "Bearer 你的24位访问密钥"
      },
      "timeout": 300
    }
  }
}
```
*如果是直连后端服务端口，URL 填写 `http://localhost:8080/mcp`。*

### MCP 图片与参考视频上传地址配置

MCP 返回的 `uploadUrl` 必须能从 AI 客户端所在机器访问。Docker 内部存储、资产预览和 MCP 上传分别配置：

| 配置 | 用途 | 本机 Docker 部署示例 |
| --- | --- | --- |
| `MINIO_ENDPOINT` | 后端读写 MinIO，Compose 已配置 | `http://minio:9000` |
| `MINIO_EXTERNAL_ENDPOINT` | 正式资产预览、下载及渲染引用 | `http://minio.localhost:9000` |
| `MINIO_MCP_UPLOAD_ENDPOINT` | 图片及视频上传申请返回的客户端直传地址 | `http://127.0.0.1:9000` |

在 `.env` 设置 `MINIO_MCP_UPLOAD_ENDPOINT`，然后执行 `docker compose up -d --no-deps backend` 应用配置。修改 `MINIO_API_PORT` 时需同步修改上传地址端口。远程客户端应填写服务器可访问的 IP 或 HTTPS 域名，不能使用客户端自己的回环地址、Docker 服务名或 MinIO 控制台端口 `9001`。反向代理必须将该地址的桶路径和 multipart POST 转发到 MinIO S3 API；使用客户端代理时，将该上传域名加入 `NO_PROXY`。

Java 直接运行时，未设置 MCP 上传地址会兼容使用 `MINIO_EXTERNAL_ENDPOINT`，不会回退到内部 `MINIO_ENDPOINT`。无需改变签名字段：客户端原样提交返回的全部 `fields`，真实文件字段最后提交，成功后以独立 `requestId` 调用 `complete_asset_image_upload`。凭证有效期为 15 分钟，过期后使用新的申请 `requestId`。

参考视频使用 `prepare_asset_video_upload` → 文件直传 → `complete_asset_video_upload`，确认后自动登记视频素材，返回可写入分镜 `refVideos` 的 `reference`。支持 MP4/WebM/MOV/MKV，默认256 MiB、600秒；可逐个上传多个文件，实际引用数量由所选模型 `maxVideos` 与网关能力配置决定。视频卡片默认折叠，上传不自动覆盖分镜或启动渲染。

### MCP 角色声音设计

重启后端后，新工具会自动补入现有能力目录；在 MCP 管理页启用写入权限和 `design_character_voice` 工具，再让客户端刷新工具列表。

- `list_voice_design_models({"dramaId":"项目ID"})`：查询已启用提供商下的 TTS / AUDIO / VOICE 模型，只返回标识和展示名称。按模型实际能力选择声音设计模型。
- `design_character_voice`：复用角色声音设计服务，调用模型并将样音归档到 MinIO。默认回填角色声音，传 `autoSave:false` 可仅试听；两种方式都会调用模型并消耗额度，均需写入权限，只允许操作授权项目的角色。

先通过 `get_character` 读取目标角色的 `editFingerprint`，再调用：

```json
{
  "dramaId": "2032095628944588801",
  "characterId": "2032095628944588802",
  "requestId": "voice-design-unique-0001",
  "expectedFingerprint": "读取角色时返回的editFingerprint",
  "providerId": "模型查询返回的providerId",
  "modelCode": "模型查询返回的modelCode",
  "voiceDesc": "青年男声，声线温和清朗，语速平稳，吐字清晰。",
  "sampleText": "你好，很高兴认识你。",
  "autoSave": true
}
```

示例 ID 须替换为真实业务 ID，所有 ID 均保持字符串。`voiceDesc` 必填，最多 512 字符；可选 `sampleText` 最多 255 字符，省略时复用角色试音文本或服务默认文本。返回 `saved`、`audio`（样音 URL、时长等）及角色当前数据（含声音字段与指纹）。调用为同步操作，可按模型耗时配置客户端超时；请求成功提交后，以相同 `requestId` 和完整原参数重试会返回原结果，不重复生成。也可通过 `get_business_operation` 查询已提交结果；服务端失败且未提交的外部模型调用无法保证不计费。

### 实际使用场景举例

连接成功后，在 **Qoder** 或 **Codex** 的对话框里直接下达自然语言指令即可：

#### 场景 1：让 AI 帮角色画设定图并自动存入系统
> **你对 AI 说：**  
> “请调用你的生图能力，为《剑来》主角‘陈平安’绘制一张草鞋少年、背负木剑的国风古装立绘；画好后，调用 Freyja 的上传工具将图片存入系统，并设为陈平安的默认造型。”
> 
> **AI 会自动执行：**  
> 1. 生成符合描述的角色立绘图片；  
> 2. 调用 `prepare_asset_image_upload` 申请上传凭证，将图片原始文件直传 MinIO，再调用 `complete_asset_image_upload` 确认归档；
> 3. 调用角色接口，将图片关联并设置为陈平安的默认形象。你在 Freyja 网页上刷新即可直接看到！

#### 场景 2：让 AI 自动拆解小说并写入分镜
> **你对 AI 说：**  
> “读取我在 Freyja 里的短剧项目《剑来》，帮我把第 1 章小说拆分成 6 个连续分镜，整理好出场人物、机位运镜、台词和视频提示词，直接写入 Freyja 并读回核对。”
> 
> **AI 会自动执行：**  
> 1. 读取项目现有资产与拍摄规约；  
> 2. 生成每一镜头的景别、运镜和规范提示词；  
> 3. 自动在系统中创建对应的场次与分镜卡片。

---

## 🛠️ 主要功能使用说明

### 1. 🤖 网页内置 AI 编剧助手
如果你没有安装外部 AI 软件，直接在 Freyja 网页右下角点击助手图标即可开始创作：
- **功能**：自动帮你把小说大纲拆解成一个个具体镜头，提炼角色、道具与场景，并准备好视频制作所需的台词与提示词；支持 DeepSeek、MiMo 等主流大模型，支持自动压缩长会话历史。
- **怎么用**：
  1. 在【系统管理 → AI 提供商管理】录入你的大模型 API Key；
  2. 在【系统管理 → Agent 配置】（`/system/agent`）勾选启用的模型与工具，点击保存启用；
  3. 打开网页对话框，直接发送指令（如：“帮我把这段小说文本拆解成 5 个镜头入库”）。

---

### 2. 🎥 MiniMax H3 视频模型、加速与人脸修复
系统支持直接调度 ComfyUI 生成高质量短剧视频，预置了 9 个主流模型入口：

| 模型代号 (在系统里选择) | 使用方式 | 步数与速度 | 人脸修复 | 适合什么场景 |
| :--- | :--- | :--- | :--- | :--- |
| **`minimax-h3-fl2va-turbo4`** | 首尾两张图生视频 | **固定 4 步（极速）** | 否 | 快速预览首尾帧镜头动效与运镜轨迹 |
| **`minimax-h3-fl2va-turbo8`** | 首尾两张图生视频 | **固定 8 步（均衡）** | 否 | 画面更加细腻的首尾帧视频输出 |
| **`minimax-h3-ref`** | 多张参考图+配音演播 | **固定 4 步（极速）** | 否 | 快速生成人物说话对话镜头 |
| **`minimax-h3-ref-turbo8`** | 多张参考图+配音演播 | **固定 8 步（均衡）** | 否 | 人物动作与微表情更加自然的对话镜头 |
| **`minimax-h3-ref-turbo4-face-refine`** | 参考图演播 + 人脸修复 | **各 4 步（快速）** | **含人脸修复** | 既想出片速度快，又担心人物脸部走样变形 |
| **`minimax-h3-ref-turbo8-face-refine`** | 参考图演播 + 人脸修复 | **各 8 步（高清）** | **含人脸修复** | 人物特写镜头首选，保证五官端正不崩脸 |
| **`minimax-h3-fl2va`** | 16G 显存优化首尾帧 | 默认 20 步 | 否 | 16G 显卡用户首尾帧备选路线 |
| **`minimax-h3-ref2va`** | 16G 显存优化参考图 | 默认 20 步 | 否 | 16G 显卡用户参考图演播备选路线 |
| **`minimax-h3-face-refine`** | 16G 显存 + 人脸修复 | 基础 20 步 | **含人脸修复** | 16G 显卡路线带人脸修复版本 |

- **首尾帧模式**：指定第一帧和最后一帧画面，AI 自动渲染中间过程，适合打斗、走位、环境变换；
- **参考图演播模式**：传入人物立绘、场景图和台词音频，AI 驱动角色神态动作演播；
- **二阶段人脸修复**：视频生成完毕后，自动用高精度模型检测人脸位置，对人脸做局部重绘增强后再贴回视频，彻底解决人物脸崩、斗鸡眼或五官扭曲问题。

---

### 3. 👥 角色长相固定与多造型换装
- **固定长相，自由换装**：角色卡片分为“基础身份”和“造型列表”。无论角色换上现代装、古代装还是战损装，长相特征均可保持统一；
- **自动识别称谓**：小说里出现的“他”、“少年”、“黑衣男子”，系统能自动识别并映射到对应的具体角色；
- **声音克隆**：支持为每个角色绑定专属配音模型，一键生成对白语音。

---

### 4. 🎞️ 抽卡多版本与后期清晰度放大
- **视频抽卡记录 (Takes)**：同一个镜头如果生成了多次，所有历史版本都会保存下来，随时可以在多个版本之间自由切换；
- **视频无损放大与补帧**：内置 SeedVR2、RealESRGAN 超分辨率放大工具，以及 RIFE 插帧工具（将 24 帧画面补成 48 帧，看起来更丝滑流畅）。

---

## 💻 电脑配置建议

| 部件 | 最低配置建议 | 推荐配置 | 说明 |
| :--- | :--- | :--- | :--- |
| **显卡 (GPU)** | **16 GB 显存**（如 RTX 4080 / RTX 3090） | **16 GB 以上显存**（如 RTX 4090） | 运行 AI 生图与视频生成模型的必要配置 |
| **电脑内存** | **32 GB** | **64 GB** | 视频生成过程中加载大模型需要充足内存 |
| **硬盘空间** | 预留 **100 GB** 以上（SSD 固态） | 预留 **200 GB** 以上（高速固态） | 存放模型文件与渲染生成的视频 |
| **操作系统** | Windows 10/11 64位 或 Ubuntu Linux | Windows 11 / Linux | 需安装 NVIDIA 独立显卡驱动 |

> 💡 **超轻量玩法推荐（笔记本也能用）**：  
> - **你的笔记本/办公电脑**：只运行 **Freyja 核心系统**（只需普通电脑配置），用来写小说、拆分镜、管理角色、用 Qoder/Codex 画图；  
> - **GPU 算力主机/云服务器**：把独立显卡电脑作为专门的 **ComfyUI 算力机**，Freyja 可以通过网络远程给它发送渲染任务，完全不卡你的工作电脑！

---

## 🚀 三步极速上手部署

---

### 第一步：启动 Freyja 核心系统 (Docker)

#### 1. 复制配置文件
克隆本项目后，在项目根目录复制一份配置文件：
```bash
cp .env.example .env
```

#### 2. 一键启动
- **直接拉取官方镜像启动（推荐，无需本地编译）：**
  ```bash
  docker compose pull
  docker compose up -d
  ```
- **Windows 用户**：直接双击根目录下的 `docker-start.bat` 脚本即可自动拉起全部服务。

#### 3. 打开网页
启动完成后，在浏览器打开：
- **🎬 创作工作台**：**[http://localhost](http://localhost)**
- **📦 MinIO 资源存储控制台**：[http://localhost:9001](http://localhost:9001)（账号: `minioadmin` 密码: `minioadmin123`）

---

### 第二步：配置 ComfyUI 算力端（用于生成视频）

在有 16G 显存 NVIDIA 独显的电脑上配置 ComfyUI：

1. **开启网络访问**：
   - 如果用秋叶启动器：点击【高级设置】 ➔ 勾选 **“允许局域网访问 (0.0.0.0)”** 与 **“开启 CORS 跨域”**，默认端口为 `8188`；
   - 如果用命令行启动：添加参数 `--listen 0.0.0.0 --port 8188 --enable-cors-header`；
2. **安装基础插件**：
   - `ComfyUI-VideoHelperSuite`（音视频合流输出）；
   - `ComfyUI-H3-FaceRefine`（人脸修复插件，放到 `custom_nodes/` 目录）；
   - `ComfyUI-Frame-Interpolation`（视频补帧）。

---

### 第三步：放置模型文件到对应目录

将下载好的模型文件放入 ComfyUI 根目录下的对应文件夹内：

#### 1. 核心生图与官方 MiniMax H3 模型

| 类型 | 文件名 | 放入哪个文件夹 (ComfyUI目录下) | 说明与下载 |
| :--- | :--- | :--- | :--- |
| **生图模型** | `flux-2-klein-9b.safetensors` | `models/unet/` | FLUX.2 生图主干 |
| **生图编码器** | `qwen_3_8b_fp8mixed.safetensors` | `models/clip/` | FLUX.2 提示词编码器 |
| **生图 VAE** | `flux2-vae.safetensors` | `models/vae/` | 图像编解码 |
| **官方 FL2VA 主模型** | `minimax_h3_fl2va_pruned_int8_convrot.safetensors` | `models/diffusion_models/` | [官方 FL2VA](https://huggingface.co/Comfy-Org/MiniMax-H3) 首尾帧生视频 |
| **官方 Ref2VA 主模型**| `minimax_h3_ref2va_pruned_int8_convrot.safetensors` | `models/diffusion_models/` | [官方 Ref2VA](https://huggingface.co/Comfy-Org/MiniMax-H3) 参考图生视频与人脸修复 |
| **官方文本编码器** | `qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors` | `models/text_encoders/` | [官方文本编码器](https://huggingface.co/Comfy-Org/MiniMax-H3) |
| **官方视频 VAE** | `minimax_h3_video_vae_int8_convrot.safetensors` | `models/vae/` | 官方视频编解码 |
| **H3 音频 VAE** | `minimax_h3_audio_vae_fp32.safetensors` | `models/vae/` | 原生音轨编解码 |

#### 2. Turbo 快速出片 LoRA 与人脸修复专用文件

| 用途 | 文件名 | 放入哪个文件夹 | 说明 |
| :--- | :--- | :--- | :--- |
| **FL2VA 4步快速** | `minimax_h3_fl2v_turbo_4step_v1.0_768p_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3)，固定 4 步 |
| **FL2VA 8步精细** | `minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3)，固定 8 步 |
| **Ref2VA 4步快速** | `minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors` | `models/loras/` | [官方 LoRA](https://huggingface.co/Comfy-Org/MiniMax-H3)，固定 4 步 |
| **Ref2VA 8步精细** | `minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors` | `models/loras/` | [LightX2V](https://huggingface.co/lightx2v/Minimax-h3-Turbo)，固定 8 步 |
| **人脸检测** | `face_yolov8m.pt` | `models/ultralytics/bbox/` | 人脸定位检测模型 |
| **人脸特征匹配** | `buffalo_l` 目录包 | `models/insightface/models/buffalo_l/` | InsightFace 身份特征模型 |

#### 3. 画面放大与补帧模型

| 用途 | 文件名 | 放入哪个文件夹 | 说明 |
| :--- | :--- | :--- | :--- |
| **SeedVR2 超分** | `seedvr2_3b_int8_convrot.safetensors` | `models/diffusion_models/` | [SeedVR2](https://huggingface.co/Comfy-Org/SeedVR2) 视频超分放大 |
| **SeedVR2 VAE** | `seedvr2_ema_vae_fp16.safetensors` | `models/vae/` | 潜空间编解码 |
| **RIFE 4.9 补帧** | `rife49.pth` | `custom_nodes/ComfyUI-Frame-Interpolation/ckpts/rife/` | 画面流畅补帧 |
| **RealESRGAN x2** | `RealESRGAN_x2plus.pth` | `models/upscale_models/` | 2 倍画面无损放大 |

---

## ⚙️ 常用管理小贴士

```bash
# 查看所有服务状态
docker compose ps

# 停止系统运行 (数据不会丢失)
docker compose stop

# 重新启动系统
docker compose start
```

> [!NOTE]
> **关于网络连接地址**：
> - 如果在 Docker 容器里配置 ComfyUI 网关地址，请填写 `http://comfy-gateway:8000/v1`；
> - 如果后端在宿主机运行调试，填写 `http://host.docker.internal:8000/v1`；
> - 切勿在容器内误填 `127.0.0.1`，否则容器会无法访问到宿主机服务。

---

## 💬 交流与反馈

- 遇到问题欢迎在 GitHub 提交 [Issues](https://github.com/Elicit1/freyja/issues) 或 Pull Request 共同改进！

<p align="center">
  <img src="image/freyja1.png" alt="Freyja Mascot" width="240" />
</p>

---

## 📄 开源许可证

本项目基于 [Apache 2.0 License](LICENSE) 协议开源。
