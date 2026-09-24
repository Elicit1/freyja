# 🎨 Freyja 配套 ComfyUI 工作流与算力端配置指南

本目录包含 **Freyja AI 短剧系统** 默认配套的 5 套核心 ComfyUI 工作流 JSON 模板，涵盖 **角色/分镜生图 (T2I)、视频生成 (FL2VA / Ref2VA)、视频补帧 (RIFE) 与超分辨率增强 (RealESRGAN)**。

---

## ⚠️ 重要声明：工作流接入机制

1. **工作流为固定绑定机制**：
   - 本系统的提示词组装流水线、首尾帧驱动、多参考图注入、音画同步与渲染进度追踪均与当前预置工作流中的**特定节点类型（Node Types）、槽位连线（Slots）与节点 ID** 深度耦合。
   - **系统不支持在 Web 界面中自动或随意加载第三方的任意 ComfyUI 工作流**，开箱默认只支持使用系统内置的这 5 套预置工作流。
2. **如需扩展新工作流**：
   - 必须通过**修改 `comfy_gateway` 适配代码与 YAML 参数映射**的方式接入（详见文末[“五、自主接入新工作流逻辑”](#五-自主接入新工作流逻辑-面向开发者)）。

---

## 一、 硬件配置要求与视频模型选型

### 1. 硬件配置推荐
由于视频生成模型（DiT 架构）体积庞大，在加载模型权重、执行张量运算及音频/视频双 VAE 解码时对硬件资源有较高要求：
- **GPU 显存 (VRAM)**：**建议 $\ge 16\text{ GB}$**（如 NVIDIA RTX 4080 16G、RTX 4090 24G、RTX 3090 24G 等）；
- **系统运行内存 (RAM)**：**宿主机内存强烈建议配置 $64\text{ GB}$**（低于 64GB 在多模型切换或处理长镜头时易触发虚拟内存频繁交换，导致任务超时或系统 OOM）。

### 2. 视频模型具体选型
系统内置的视频生成工作流基于 ModelScope 开源的 **[MiniMax H3 混合高精度量化 (16G显存优化版)](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)** 打造，可在 16G 显存设备上稳定运行。

---

## 二、 工作流清单与应用场景

| 工作流文件 | 核心模型 | 任务类型 | 对应 Freyja 业务场景 |
| :--- | :--- | :--- | :--- |
| `flux2_klein_9b_t2i_r3.json` | FLUX.2 Klein 9B + Qwen3 8B CLIP | 文本/参考图生图 (T2I) | 角色设定参考图、造型图、分镜首帧/关键帧生成 |
| `minimax_h3_fl2va_fast.json` | MiniMax H3 (FL2VA-Fast) | 首尾帧生视频带音效 (I2V) | 分镜「首尾帧驱动」影视镜头生成、镜头组尾帧续接 |
| `minimax_h3_ref2va_fast.json` | MiniMax H3 (Ref2VA-Fast) | 参考图+音频驱动视频 | 分镜「多参考图+台词配音」音画同步角色演播 |
| `h3_rife49_48fps.json` | RIFE 4.9 (VFI) | 视频平滑补帧 | 视频工坊：24 FPS 智能插帧平滑至 48 FPS 影视高帧率 |
| `realesrgan_x2_video.json` | RealESRGAN x2plus | 视频超分辨率重建 | 视频工坊：720P/1080P 镜头 2 倍无损超分高清放大 |

---

## 三、 依赖的 ComfyUI 自定义插件 (Custom Nodes)

请在您的 ComfyUI 中通过 **ComfyUI-Manager** 搜索安装，或在 `ComfyUI/custom_nodes/` 目录下 `git clone` 安装以下节点插件：

### 1. 核心多媒体与通用工具（必装）
- **ComfyUI-VideoHelperSuite** (`VHS`)
  - 作用：支持视频加载、音视频合流输出与帧序列管理。
- **ComfyUI-Frame-Interpolation**
  - 作用：驱动 RIFE 4.9 视频补帧插帧算法。

### 2. 模型专属扩展插件
- **ComfyUI-sol-attn**：
  - **作用**：针对 MiniMax H3 模型的 Attention 运算进行针对性显存优化与推理加速；
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
│   ├── unet/                                # 主干 DiT / UNET 模型
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
│   │   ├── minimax_h3_video_vae_fp16.safetensors # 来自 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)
│   │   └── minimax_h3_audio_vae_fp32.safetensors # 来自 [ModelScope MiniMax-H3-16G-HiFi](https://www.modelscope.cn/models/l1ngzi/MiniMax-H3-16G-HiFi)
│   │
│   └── upscale_models/                      # 超分辨率模型
│       └── RealESRGAN_x2plus.pth
│
└── custom_nodes/
    ├── ComfyUI-sol-attn/                    # MiniMax H3 加速插件 (来自 ModelScope 仓库文件 ComfyUI-sol-attn-main.zip)
    └── ComfyUI-Frame-Interpolation/
        └── ckpts/
            └── rife/
                └── rife49.pth               # RIFE 4.9 补帧权重
```

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
