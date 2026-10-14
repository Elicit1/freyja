# 🎨 Freyja 配套 ComfyUI 工作流与算力端配置指南

本目录包含 **Freyja AI 短剧系统** 配套 ComfyUI UI 工作流，涵盖 **FLUX.2 生图、MiniMax H3 原 16GB HiFi 与官方 FL2VA / Ref2VA、Turbo4 / Turbo8、人脸修复、RIFE 补帧、RealESRGAN 与 SeedVR2 超分**。下表按默认节点实际注册的 13 个入口列出对应文件，目录另有原始、保存版及显存优化示例。

---

## ⚠️ 重要声明：工作流接入机制

1. **工作流为固定绑定机制**：
   - 本系统的提示词组装流水线、首尾帧驱动、多参考图注入、音画同步与渲染进度追踪均与当前预置工作流中的**特定节点类型（Node Types）、槽位连线（Slots）与节点 ID** 深度耦合。
   - 平台渲染按 [默认节点配置](../comfy_gateway/nodes/machine_default.yaml) 中的模型编码、API 图与 handler 调用预置工作流；新导入的 UI JSON 需要完成相同的适配与注册后才能通过平台调度。
2. **如需扩展新工作流**：
   - 必须通过**修改 `comfy_gateway` 适配代码与 YAML 参数映射**的方式接入（详见文末[“五、自主接入新工作流逻辑”](#五-自主接入新工作流逻辑-面向开发者)）。

---

## 一、 硬件配置要求与视频模型选型

### 1. 硬件配置推荐

| 配置项 | 最低要求 (Minimum) | 推荐配置 (Recommended) | 说明 |
| :--- | :--- | :--- | :--- |
| **GPU 独立显卡** | **16 GB 显存**（如 RTX 4080 16G / RTX 3090 24G 等） | **16 GB 以上显存**（如 RTX 4090 24G 或专业级工作站显卡） | FLUX.2 Klein 与 MiniMax H3 视频 DiT 大权重加载与推理的硬性基准 |
| **宿主机内存 (RAM)** | **32 GB** | **64 GB 及以上** | 视频模型大权重加载与多阶段合流在 32G 下容易触碰虚拟内存交换上限，推荐 64G 获得最佳流水线流畅度 |
| **磁盘存储** | 预留 **100 GB** 以上（SSD 固态硬盘） | 预留 **200 GB** 以上（NVMe M.2 高速固态） | 存放基础模型权重、缓存与高清视频渲染资产 |
| **操作系统** | Windows 10/11 64位 或 Linux (Ubuntu 20.04/22.04 LTS) | Windows 11 / Linux (Ubuntu 22.04+ LTS) | 支持 NVIDIA 专有驱动、CUDA 环境与 Docker 编排 |

### 2. 视频模型具体选型
视频工作流同时提供 [原 16GB HiFi 权重](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi) 与 [Comfy-Org 官方 INT8 ConvRot 权重](https://huggingface.co/Comfy-Org/MiniMax-H3) 两条路线。官方 FL2VA / Ref2VA 共用官方 Qwen3-VL 编码器和音视频 VAE，按入口选择匹配的四步 / 八步 LoRA；人脸修复在 Ref2VA 生成后增加裁剪重绘阶段。分辨率、时长、修复阶段与节点缓存策略都会影响显存需求。

---

## 二、 工作流清单与应用场景

| 工作流文件 | 网关模型编码 | 任务类型 | 使用说明 |
| :--- | :--- | :--- | :--- |
| `flux2_klein_9b_t2i_r3.json` | `flux-2-klein-9b` | 文本 / 参考图生图 | 角色设定图、造型图、分镜首帧与关键图 |
| `minimax_h3_fl2va_fast.json` | `minimax-h3-fl2va` | 首尾帧视频与原生音频 | 原 16GB HiFi，默认 20 步 |
| `minimax_h3_ref2va_fast.json` | `minimax-h3-ref2va` | 多参考图 / 音频视频 | 原 16GB HiFi，默认 20 步 |
| `video_minimax_h3_i2v_official_turbo4.json` | `minimax-h3-fl2va-turbo4` | 官方文生 / 首帧 / 首尾帧 | 固定 4 步，FL2V 768p LoRA；[部署与下载](../docs/minimax-h3-fl2va-turbo4.md) |
| `video_minimax_h3_i2v_official_turbo8.json` | `minimax-h3-fl2va-turbo8` | 官方文生 / 首帧 / 首尾帧 | 固定 8 步，当前使用标准 544p LoRA；[接入说明](../docs/minimax-h3-fl2va-turbo8.md) |
| `video_minimax_h3_r2v_official_turbo4.json` | `minimax-h3-ref` | 官方多参考图 / 音频视频 | 固定 4 步，Ref2V v0.1 LoRA |
| `video_minimax_h3_r2v_official_turbo8.json` | `minimax-h3-ref-turbo8` | 官方多参考图 / 音频视频 | 固定 8 步，Ref2V 768p LoRA；[接入说明](../docs/minimax-h3-ref2va-turbo8.md) |
| `minimax-h3-face-refine.json` | `minimax-h3-face-refine` | 原 Ref2VA 与人脸修复 | 二阶段修复，基础生成默认 20 步 |
| `video_minimax_h3_r2v_turbo4_face_refine.json` | `minimax-h3-ref-turbo4-face-refine` | 官方 Ref2VA 与人脸修复 | 生成 / 修复均为 4 步 |
| `video_minimax_h3_r2v_turbo8_face_refine.json` | `minimax-h3-ref-turbo8-face-refine` | 官方 Ref2VA 与人脸修复 | 生成 / 修复均为 8 步 |
| `h3_rife49_48fps.json` | `rife49-video-48fps` | 视频平滑补帧 | 24 FPS 标准化后输出 48 FPS |
| `realesrgan_x2_video.json` | `realesrgan-x2-video` | 视频超分 | RealESRGAN x2 |
| `utility_seedvr2_3b_int8_upscale_video.json` | `seedvr2-3b-int8-video` | 视频超分 | SeedVR2 3B INT8 原生工作流与时间分块 |

平台调用使用 `comfy_gateway/comfyUIApi/` 中的 API 图。UI 文件用于在 ComfyUI 中查看、选择权重与调试；修改 UI 后，需要同步 API 图、节点映射及适配器才能改变平台渲染行为。Turbo 入口固定采样步数，通用 `steps` 参数不会替换四步 / 八步 LoRA 的约定。

---

## 三、 依赖的 ComfyUI 自定义插件 (Custom Nodes)

请在您的 ComfyUI 中通过 **ComfyUI-Manager** 搜索安装，或在 `ComfyUI/custom_nodes/` 目录下 `git clone` 安装以下节点插件：

### 1. 核心多媒体与通用工具（必装）
- **ComfyUI-VideoHelperSuite** (`VHS`)
  - 作用：支持视频加载、音视频合流输出与帧序列管理。
- **ComfyUI-Frame-Interpolation**
  - 作用：驱动 RIFE 4.9 视频补帧插帧算法。

### 2. 模型专属扩展插件
- **官方 H3 原生节点**：使用足够新的 ComfyUI，确认 `MiniMaxH3ImageToVideo`、`MiniMaxH3ReferenceToVideo`、`LoraLoaderModelOnly` 与 `res_multistep` 可用；官方 INT8 入口的环境要求见对应部署说明。
- **ComfyUI-H3-FaceRefine**：安装 [节点包](https://github.com/Carasibana/ComfyUI-H3-FaceRefine) 及其依赖，在 `models/ultralytics/bbox/` 准备 `face_yolov8m.pt`。使用 InsightFace 身份匹配时，`buffalo_l` 按需下载至 `models/insightface/models/buffalo_l/`；离线环境提前准备。当前模板通过这些节点完成逐帧跟踪、裁剪、重绘和贴回。
- **ComfyUI-sol-attn**：
  - **作用**：用于原 16GB HiFi 工作流的 Attention 优化；官方 INT8 工作流按自身节点清单准备环境；
  - **下载与安装**：从 [ModelScope MiniMax-H3-16G-HiFi 文件列表](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi/files) 下载 `ComfyUI-sol-attn-main.zip`，解压至 `ComfyUI/custom_nodes/ComfyUI-sol-attn` 目录。
- **MiniMax H3 ComfyUI 专属节点集**：提供 MiniMax H3 的 DiT 调度、首尾帧与参考图驱动算子。
- **Flux.2 / ReferenceLatent 节点扩展**：提供 FLUX.2 特征注入与参考图潜空间控制支持。
- **ComfyUI 常用计算与解析节点**：`ComfyMathExpression`、`ResolutionSelector`。

---

## 四、 模型权重文件放置规范 (Models Directory Layout)

请将从 ModelScope / HuggingFace 下载好的模型权重分别放入 ComfyUI 根目录下的对应文件夹内（文件名称需保持一致）：

```text
ComfyUI/
├── models/
│   ├── diffusion_models/                    # 官方 H3 与 SeedVR2 主模型
│   │   ├── minimax_h3_fl2va_pruned_int8_convrot.safetensors
│   │   ├── minimax_h3_ref2va_pruned_int8_convrot.safetensors
│   │   └── seedvr2_3b_int8_convrot.safetensors
│   ├── text_encoders/                       # 官方 H3 编码器
│   │   └── qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors
│   ├── loras/                               # 按所选入口准备
│   │   ├── minimax_h3_fl2v_turbo_4step_v1.0_768p_comfyui_bf16.safetensors
│   │   ├── minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors
│   │   ├── minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors
│   │   └── minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors
│   ├── ultralytics/bbox/
│   │   └── face_yolov8m.pt
│   ├── insightface/models/buffalo_l/          # 使用 InsightFace 时按需下载
│   ├── unet/                                # FLUX.2 与原 16GB HiFi 主模型
│   │   ├── flux-2-klein-9b.safetensors
│   │   ├── minimax_h3_fl2va_dit_16g.safetensors  # 来自 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)
│   │   └── minimax_h3_ref2va_dit_16g.safetensors  # 来自 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)
│   │
│   ├── clip/                                # 文本与多模态特征编码器
│   │   ├── qwen_3_8b_fp8mixed.safetensors
│   │   └── qwen3vl_text_encoder.safetensors      # 来自 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)
│   │
│   ├── vae/                                 # 图像与音视频编解码器
│   │   ├── flux2-vae.safetensors
│   │   ├── minimax_h3_video_vae_int8_convrot.safetensors
│   │   ├── seedvr2_ema_vae_fp16.safetensors
│   │   ├── minimax_h3_video_vae_fp16.safetensors # 来自 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)
│   │   └── minimax_h3_audio_vae_fp32.safetensors # 来自 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)
│   │
│   └── upscale_models/                      # 超分辨率模型
│       └── RealESRGAN_x2plus.pth
│
└── custom_nodes/
    ├── ComfyUI-H3-FaceRefine/                # 二阶段人脸修复节点
    ├── ComfyUI-sol-attn/                    # MiniMax H3 加速插件 (来自 ModelScope 仓库文件 ComfyUI-sol-attn-main.zip)
    └── ComfyUI-Frame-Interpolation/
        └── ckpts/
            └── rife/
                └── rife49.pth               # RIFE 4.9 补帧权重
```

官方 H3 主模型、编码器、VAE 及 FL2V / Ref2V 四步 LoRA 来源为 [Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3)，Ref2V 八步 768p LoRA 来源为 [LightX2V](https://huggingface.co/lightx2v/Minimax-h3-Turbo)，人脸检测器来源为 [Bingsu/adetailer](https://huggingface.co/Bingsu/adetailer)。SeedVR2 的主模型与 VAE 来源为 [Comfy-Org/SeedVR2](https://huggingface.co/Comfy-Org/SeedVR2)。主模型和 LoRA 文件名应与所选 UI / API 图一致；不同步数或目标分辨率的 LoRA 不能只靠修改 `steps` 互换。

### ComfyUI 启动参数要求
- **Windows 秋叶启动器**：高级设置勾选 **“允许局域网访问 (监听 0.0.0.0)”** 与 **“开启 CORS 跨域”**；
- **命令行启动**：添加参数 `--listen 0.0.0.0 --port 8188 --enable-cors-header`。

---

## 五、 自主接入新工作流逻辑 (面向开发者)

若您具备一定的开发能力，希望替换或接入自己调优的工作流（如特定的 SDXL、HunyuanVideo 或 Wan2.1）：

### 1. 接入技术链路
1. **导出 API 格式 JSON**：
   在 ComfyUI 页面完成连线测试后，打开设置中的 **Dev Mode (开发者模式)**，点击 **“Save (API Format)”** 导出为纯参数槽位组成的 API JSON（注意：非普通的带 UI 坐标的 JSON），放置到 `comfy_gateway/comfyUIApi/` 目录；
2. **在网关中配置槽位映射 (YAML)**：
   在 `comfy_gateway/nodes/*.yaml`（或本地调试配置 `machine_local.yaml`）中新增该模型定义，配置参数映射关系（如提示词、种子、步数、CFG 分别注入到哪个 Node ID 的 inputs 键下）；
3. **编写/修改网关适配器代码**：
   若该工作流涉及非标准的图片/音频预处理或多图拼接，在 `comfy_gateway/` 的 `adapters.py` 或 `workflow_engine.py` 中编写相应的 `handler` 处理函数。

### 2. 借助 AI 辅助工具快速接入（推荐）
您可以直接利用本地/在线的代码大模型（如 **Gemini、Codex、Claude Code、Cursor、OpenCode** 等）协助完成代码编写：
- **Prompt 提示词模板**：
  > “我导出了一个 ComfyUI API 格式的工作流 JSON（附上 JSON 内容），请参考 comfy_gateway/adapters.py 与 machine_default.yaml 中的现有实现规范，帮我编写该工作流的参数映射配置字典（mappings）以及对应的 Python Handler 适配函数，使其能通过 FastAPI 网关接收 prompt、seed 与图片入参并提交给 ComfyUI 执行。”
