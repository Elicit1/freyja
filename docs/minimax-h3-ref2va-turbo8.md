# MiniMax H3 Ref2VA 八步加速与人脸修复

两个工作流基于 Comfy-Org 官方 Ref2VA INT8 图与 LightX2V 发布的 **Ref2VA Turbo 8step v1.0 768p ComfyUI LoRA**，使用 `res_multistep` 采样器、`simple` 调度器和 LoRA 强度 1.0。人脸修复版在官方 Ref2VA 图后组合现有 H3 FaceRefine 节点。

| ComfyUI 工作流 | 网关模型编码 | 采样方式 |
|---|---|---|
| `video_minimax_h3_r2v_official_turbo8.json` | `minimax-h3-ref-turbo8` | 固定 8 步 |
| `video_minimax_h3_r2v_turbo8_face_refine.json` | `minimax-h3-ref-turbo8-face-refine` | 首段 8 步，修复段 8 步，修复 denoise=0.4 |

工作流保存在 ComfyUI 默认用户的 `workflows/` 目录中。刷新 ComfyUI 工作流列表即可打开。仓库中的对应 UI JSON 位于 `comfyUI_workflow/`，API 格式位于 `comfy_gateway/comfyUIApi/`。

两版默认使用 1344×768、24 FPS、5 秒。`768p` 表示 LoRA 的训练目标和推荐尺寸，未限制网关仅生成 768p：请求仍可传入 960×544 等其他尺寸，按 32 像素对齐，质量需实测。网关只映射 Prompt、尺寸、时长、种子和模型文件，通用 `steps` 入参不会将固定八步误改为四步或二十步。

两个模型的 FREYJA 类型均为 `TXT2VIDEO_REF`，支持最多 5 张参考图片、3 段参考音频。人脸修复版将相同参考图片传入两个阶段，修复阶段使用首段生成的音频，最终输出修复后画面与首段原生音频。

## 八步 LoRA 镜像下载

Ref2VA 八步 v1.0 768p 权重发布于 [lightx2v/Minimax-h3-Turbo](https://huggingface.co/lightx2v/Minimax-h3-Turbo)，不在当前 Comfy-Org 权重文件列表中。不能替换成 FL2VA LoRA，也不能直接将已有 Ref2VA 四步 LoRA 的步数改成八步。

GPU 节点已检测到所需主模型、文本编码器、两个 VAE 和 H3 FaceRefine 节点。八步 LoRA 在接入时尚未安装。在运行 ComfyUI 的 GPU 节点 WSL 执行：

```bash
cd ~/ComfyUI-Official
mkdir -p models/loras
wget -c -O models/loras/minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors \
  'https://hf-mirror.com/lightx2v/Minimax-h3-Turbo/resolve/main/minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors'
```

下载后刷新 ComfyUI 模型列表；刷新后仍没有新 LoRA 时，在已有渲染任务结束后重启 ComfyUI。人脸修复节点沿用当前 `face_yolov8m.pt` 检测器配置。首次使用工作流时选择自己的参考图片和音频。

## 部署与初始化

在 FREYJA 仓库根目录执行。当前 WSL 使用的 ComfyUI 地址与 FastApi 提供商 ID 如下，其他环境替换为自己的配置：

```bash
docker compose build comfy-gateway
# 等待当前渲染结束；较长的停止超时也让已有网关请求有时间完成。
docker compose up -d --no-deps --timeout 1800 comfy-gateway
python3 docker/scripts/init_minimax_h3_ref2va_turbo8.py \
  --comfy-url http://100.81.173.100:8188 \
  --base-url http://127.0.0.1:8080 \
  --provider-id 2096886255638646785
```

初始化脚本通过 ComfyUI `/userdata` 保存工作流并读回验证，通过 FREYJA 模型 API 幂等注册两个条目并触发缓存失效。已有同名 ComfyUI 文件若内容不同，脚本会保留该文件并报错；已有四步与二十步工作流不受影响。平台 Snowflake ID 全程使用字符串。

验证网关 `/v1/models` 包含两个新模型，FREYJA 提供商模型列表包含对应 `TXT2VIDEO_REF` 条目。自动测试覆盖固定八步、图片与音频绑定、人脸修复的第二阶段采样链、原生音频合流及 API 列表。权重下载完成前不执行实际 GPU 生视频。
