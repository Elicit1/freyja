# SeedVR2 与本地 FlashVSR 超分接入

## 设计与数据流

超分中心继续从 `ai_model` 中读取 `VIDEO_UPSCALE` 模型及受控参数。任务使用不可变来源 URL，Java 服务将倍率、CRF、音轨开关及经模型白名单校验的推理模式传给 Comfy Gateway。Gateway 根据模型代码装载独立 API 工作流，将源视频上传到 ComfyUI，取回输出并交由 Java 归档到 MinIO。历史 Take 与分镜保存路径保持现有机制。

| 模型代码 | 工作流 | 倍率 | 默认模式 | 显存策略 |
| --- | --- | --- | --- | --- |
| `seedvr2-3b-int8-video` | `utility_seedvr2_3b_int8_upscale_video` | 2× | 自动时序分块 | INT8 UNet、Tiled VAE、TemporalChunk/Merge |
| `flashvsr-video` | `utility_flashvsr_upscale_video` | 2×/4× | `tiny-long` | 状态连续推理、模型 CPU 卸载、输入原分辨率 |

SeedVR2 使用远端已安装的 `seedvr2_3b_int8_convrot.safetensors` 与 `seedvr2_ema_vae_fp16.safetensors`。FlashVSR 使用 [DNPMBHC/ComfyUI-FlashVSR](https://github.com/DNPMBHC/ComfyUI-FlashVSR) 本地节点；WaveSpeed FlashVSR 云端节点不参与此工作流。FlashVSR 插件及权重安装前，数据库模型保持停用，以免用户提交必然失败的任务。安装完成并重启 ComfyUI 后，确认 `/object_info` 存在 `FlashVSRNode`，再启用 `flashvsr-video`。

## 在 GPU 节点 WSL 中逐条执行

以下命令使用你当前的 `~/ComfyUI-Official` 及 `minimax-h3` 环境。SeedVR2 权重已在远端发现；若文件缺失，可重新下载。

```bash
cd ~/ComfyUI-Official
mkdir -p models/diffusion_models models/vae
wget -c -O models/diffusion_models/seedvr2_3b_int8_convrot.safetensors 'https://hf-mirror.com/Comfy-Org/SeedVR2/resolve/main/diffusion_models/seedvr2_3b_int8_convrot.safetensors'
wget -c -O models/vae/seedvr2_ema_vae_fp16.safetensors 'https://hf-mirror.com/Comfy-Org/SeedVR2/resolve/main/vae/seedvr2_ema_vae_fp16.safetensors'
```

本地 FlashVSR 插件与权重：

```bash
cd ~/ComfyUI-Official/custom_nodes
git clone https://github.com/DNPMBHC/ComfyUI-FlashVSR.git
python -m pip install -r ComfyUI-FlashVSR/requirements.txt
cd ~/ComfyUI-Official
mkdir -p models/FlashVSR
wget -c -O models/FlashVSR/LQ_proj_in.ckpt 'https://hf-mirror.com/JunhaoZhuang/FlashVSR-v1.1/resolve/main/LQ_proj_in.ckpt'
wget -c -O models/FlashVSR/TCDecoder.ckpt 'https://hf-mirror.com/JunhaoZhuang/FlashVSR-v1.1/resolve/main/TCDecoder.ckpt'
wget -c -O models/FlashVSR/diffusion_pytorch_model_streaming_dmd.safetensors 'https://hf-mirror.com/JunhaoZhuang/FlashVSR-v1.1/resolve/main/diffusion_pytorch_model_streaming_dmd.safetensors'
wget -c -O models/FlashVSR/Wan2.1_VAE.pth 'https://hf-mirror.com/JunhaoZhuang/FlashVSR-v1.1/resolve/main/Wan2.1_VAE.pth'
wget -c -O models/FlashVSR/lightvaew2_1.pth 'https://hf-mirror.com/lightx2v/Autoencoders/resolve/main/lightvaew2_1.pth'
```

插件安装后重启 ComfyUI。先用 3–5 秒、较低分辨率片段验证画面和音轨。FlashVSR 的 `tiny-long` 应保持 `frame_chunk_size=0`，避免破坏时序状态。4080 处理 2K 或长视频仍可能 OOM，先按原片尺寸和目标尺寸估算，再尝试完整片段。

## 部署与核对

在 Freyja 项目根目录运行 `docker compose build comfy-gateway backend frontend`，再运行 `docker compose up -d --no-deps comfy-gateway backend frontend`。核对网关 `/v1/models` 和 Freyja `/video-processing/models?providerId=...&operation=VIDEO_UPSCALE`。FlashVSR 节点及权重确认后启用数据库中的 `flashvsr-video`。
