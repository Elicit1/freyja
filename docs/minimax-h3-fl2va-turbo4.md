# MiniMax H3 官方 FL2VA 四步加速接入

模型中心选择 **MiniMax H3 官方 FL2VA Turbo4**，模型编码为 `minimax-h3-fl2va-turbo4`，类型为 `TXT2VIDEO_FIRST_LAST`。支持不传图片的文生视频、仅首帧、首尾帧三种模式，最多两张图片；声音由 H3 原生生成，不接收独立参考音频/视频。

## 官方来源与网关适配

- 官方模板：[Comfy-Org/video_minimax_h3_i2v.json](https://github.com/Comfy-Org/workflow_templates/blob/main/templates/video_minimax_h3_i2v.json)
- 官方权重：[Comfy-Org/MiniMax-H3](https://huggingface.co/Comfy-Org/MiniMax-H3)
- Turbo 蒸馏 LoRA：[LightX2V/Minimax-H3-Turbo](https://github.com/ModelTC/Minimax-H3-Turbo)

`comfyUI_workflow/video_minimax_h3_i2v_official_turbo4.json` 基于官方模板，开启 `turbo_mode`，替换为 FL2V **4step v1.0 768p** LoRA，`turbo_steps=4`，LoRA 强度为 1.0。官方模板原本提供 8 步 LoRA 选项，不能仅修改步数来替代四步 LoRA。

`comfy_gateway/comfyUIApi/minimax_h3_fl2va_official_turbo4.json` 将官方子图展开成 API 图，保留原节点 ID，固定 Turbo 分支。采样链为 `UNETLoader → LoraLoaderModelOnly → BasicGuider / BasicScheduler → SamplerCustomAdvanced`，采样器为 `res_multistep`，调度器为 `simple`，4 步；生成的同一 latent 分别解码视频和音频，再以 24 FPS 合流保存。网关注入 Prompt、尺寸、17k+5 对齐帧数、种子和可选首尾帧，因此省去 UI 分辨率与时长计算节点、演示图片和 Turbo 切换节点。

公共节点配置和本地覆盖配置均注册了新模型。此入口不映射 `steps` 或 LoRA 参数，传入通用 20 步参数也不会覆盖四步采样。现有 `minimax-h3-fl2va` 20 步入口保留。

## 模型下载（Hugging Face 镜像）

在**运行 ComfyUI 的 GPU 节点** WSL 执行。当前 GPU 节点已从 `/object_info` 检测到主模型、文本编码器和两个 VAE，仅缺少下列四步 LoRA（约 1.82 GB）；现有八步或 Ref2VA LoRA 不能替代它。

```bash
cd ~/ComfyUI-Official
mkdir -p models/loras
wget -c -O models/loras/minimax_h3_fl2v_turbo_4step_v1.0_768p_comfyui_bf16.safetensors \
  'https://hf-mirror.com/Comfy-Org/MiniMax-H3/resolve/main/loras/minimax_h3_fl2v_turbo_4step_v1.0_768p_comfyui_bf16.safetensors'
```

如果迁移至新 GPU 节点，另需下面四个文件（当前节点无需重复下载）：

```bash
cd ~/ComfyUI-Official
mkdir -p models/diffusion_models models/text_encoders models/vae
wget -c -O models/diffusion_models/minimax_h3_fl2va_pruned_int8_convrot.safetensors \
  'https://hf-mirror.com/Comfy-Org/MiniMax-H3/resolve/main/diffusion_models/minimax_h3_fl2va_pruned_int8_convrot.safetensors'
wget -c -O models/text_encoders/qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors \
  'https://hf-mirror.com/Comfy-Org/MiniMax-H3/resolve/main/text_encoders/qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors'
wget -c -O models/vae/minimax_h3_video_vae_int8_convrot.safetensors \
  'https://hf-mirror.com/Comfy-Org/MiniMax-H3/resolve/main/vae/minimax_h3_video_vae_int8_convrot.safetensors'
wget -c -O models/vae/minimax_h3_audio_vae_fp32.safetensors \
  'https://hf-mirror.com/Comfy-Org/MiniMax-H3/resolve/main/vae/minimax_h3_audio_vae_fp32.safetensors'
```

官方 INT8 ConvRot 权重优先使用 PyTorch CUDA 13.0 构建。当前 GPU 节点报告 `2.13.0+cu130`，已包含 `MiniMaxH3ImageToVideo`、`LoraLoaderModelOnly`、`res_multistep` 等原生节点，无需安装原 16GB HiFi 工作流的 SolAttention、FusedModulation、ChunkFeedForward 或 EasyCache 插件。

下载结束后刷新 ComfyUI 模型列表；如果 `LoraLoaderModelOnly` 下拉列表仍没有新文件，重启该 GPU 节点的 ComfyUI，再选择平台新模型生成。

## 部署与平台初始化

在 FREYJA 所在 WSL 的仓库根目录执行：

```bash
docker compose build comfy-gateway
docker compose up -d --no-deps comfy-gateway
python3 docker/scripts/init_minimax_h3_fl2va.py \
  --base-url http://127.0.0.1:8080 \
  --provider-id 2096886255638646785
```

`--provider-id` 使用当前平台已有的 FastApi 提供商 ID；其他部署替换为自己的 Comfy Gateway 提供商 ID。初始化脚本通过平台 API 幂等新增/更新模型，ID 全程作为字符串，使用平台缓存失效流程，并验证模型可出现在启用列表。它只初始化该模型，不改变提供商配置或已有模型。

核对网关 `GET http://127.0.0.1:8000/v1/models` 包含 `minimax-h3-fl2va-turbo4`，平台 `GET http://127.0.0.1:8080/ai/provider/2096886255638646785/model/list` 包含新模型。推荐先以 5 秒、1344×768 官方 768p 尺寸生成；也可按显存预算选择现有 960×544 档位。

四步 LoRA 下载完成前，网关和模型中心可初始化，但实际生成仍会被 ComfyUI 判定为缺少权重。自动验证覆盖三种帧输入模式、首尾帧连接、四步采样保护、参数注入与原生音频合流；实际 GPU 生视频需下载完成后验证。
