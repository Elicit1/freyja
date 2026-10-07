# SeedVR2 3B INT8 视频超分接入

超分中心从 `ai_model` 读取 `VIDEO_UPSCALE` 模型及受控参数。Java 服务将来源视频 URL、倍率、CRF 和音轨开关传给 Comfy Gateway。Gateway 加载 `utility_seedvr2_3b_int8_upscale_video_api.json`，将视频上传至 ComfyUI，获取产物后由 Java 归档到 MinIO。分镜历史 Take 沿用现有机制。

工作流 `utility_seedvr2_3b_int8_upscale_video` 使用 INT8 UNet、Tiled VAE、自动时序分块与合并，当前在模型中心只开放 2 倍倍率。

远端 ComfyUI 已检测到以下权重。若文件缺失，在 GPU 节点 WSL 中逐条执行：

```bash
cd ~/ComfyUI-Official
mkdir -p models/diffusion_models models/vae
wget -c -O models/diffusion_models/seedvr2_3b_int8_convrot.safetensors 'https://hf-mirror.com/Comfy-Org/SeedVR2/resolve/main/diffusion_models/seedvr2_3b_int8_convrot.safetensors'
wget -c -O models/vae/seedvr2_ema_vae_fp16.safetensors 'https://hf-mirror.com/Comfy-Org/SeedVR2/resolve/main/vae/seedvr2_ema_vae_fp16.safetensors'
```

部署网关、后端和前端：

```bash
docker compose build comfy-gateway backend frontend
docker compose up -d --no-deps comfy-gateway backend frontend
```

核对 `/v1/models` 与 `/video-processing/models?providerId=...&operation=VIDEO_UPSCALE`。
