# Pika H3 Flex 接入

两套入口复用官方 MiniMax H3 INT8 底模、Qwen3-VL 编码器和音视频 VAE，分别加载对应 Pika BF16 LoRA。源自 Comfy-Org 官方 [FL2VA 模板](https://github.com/Comfy-Org/workflow_templates/blob/main/templates/video_pika_h3_flex_fl2va.json)与 [Ref2VA 模板](https://github.com/Comfy-Org/workflow_templates/blob/main/templates/video_pika_h3_flex_ref2va.json)。本仓库提供相同采样链路的平铺 UI 图，方便查看与平台调度。

| 模型编码 | 平台类型 | UI 工作流 | API 图 |
| --- | --- | --- | --- |
| `pika-h3-flex-fl2va` | `TXT2VIDEO_FIRST_LAST` | `comfyUI_workflow/video_pika_h3_flex_fl2va.json` | `comfy_gateway/comfyUIApi/pika_h3_flex_fl2va.json` |
| `pika-h3-flex-ref2va` | `TXT2VIDEO_REF` | `comfyUI_workflow/video_pika_h3_flex_ref2va.json` | `comfy_gateway/comfyUIApi/pika_h3_flex_ref2va.json` |

## 权重与采样

- FL2VA：`minimax_h3_fl2va_pruned_int8_convrot.safetensors` + `pika_h3_flex_fl2va_v1_bf16.safetensors`。
- Ref2VA：`minimax_h3_ref2va_pruned_int8_convrot.safetensors` + `pika_h3_flex_ref2va_v1_bf16.safetensors`。
- 共用：`qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors`、`minimax_h3_video_vae_int8_convrot.safetensors`、`minimax_h3_audio_vae_fp32.safetensors`。
- 底模、LoRA、编码器、VAE 分别存放于 `models/diffusion_models/`、`models/loras/`、`models/text_encoders/`、`models/vae/`。
- `LoraLoaderModelOnly` 强度 1，`BasicGuider`，`KSamplerSelect=lcm`，`ManualSigmas=1.0, 0.972973, 0.923077, 0.8, 0.571429, 0.333333, 0.0`。

一采的六个区间即六步采样。不能用通用六步 Euler/BasicScheduler 替换，也不叠加原 Turbo4/8 LoRA。YAML 不映射 `steps`、`cfg`、`sampler` 或 `sigmas`，通用请求覆盖参数不会改变一采发布调度。

## ComfyUI 与网关

ComfyUI 需要支持 `MiniMaxH3ImageToVideo`、`MiniMaxH3ReferenceToVideo`、`ManualSigmas`、`lcm`、原生音视频解码与 `SaveVideo`。UI 图默认 832×480、124 帧、24 FPS。宽高为 32 的整数倍，输出帧数为 `17k+5`。网关按用户请求注入尺寸、时长、种子和提示词。

`comfyUI_workflow/examples/` 包含两张程序绘制的中性测试帧。直接使用 UI 示例时，先上传图片或选择自己的图片。FL2VA 可断开尾帧做首帧生成，断开全部图片做文生视频；Ref2VA 可连接参考图和独立音频。

两个模型均已加入 `comfy_gateway/nodes/machine_default.yaml`。私有 `machine_local.yaml` 会覆盖同名节点的整个模型列表，使用私有配置时必须同时加入两个模型定义。更改后重建网关镜像并重新创建服务：

```bash
docker compose build comfy-gateway
docker compose up -d --no-deps comfy-gateway
```

确认 `GET /v1/models` 返回两个编码，再在 FREYJA 模型中心使用相同编码注册。FL2VA 配置最多 2 图、0 音频参考、0 视频参考；Ref2VA 配置最多 9 图、3 音频参考、0 视频参考。两者均输出原生生成音轨；“0 音频参考”不影响输出声音。

Ref2VA 网关已登记参考视频适配，平台仍按模型中心的 `maxVideos` 和媒体限制收紧预算。能力声明不代表 GPU 画质验收；请求须绑定 Pika 自己的当前工作流 revision，不能复制其他 H3 模型的 revision。

## 可配置放大与人脸修复

`pika-h3-flex-ref2va` 可在基础模型上开启放大、人脸修复，分别执行独立的 `UPSCALE`、`FACE` 阶段，不需要选择额外的人脸修复型号。FL2VA 仍不提供这些增强阶段。

增强保留 Pika LoRA 与 LCM，按 `denoise` 选择一采 Sigma 曲线的末尾区间，在该区间内分段线性重采样。放大按 `upscaleSteps` 输出对应数量的采样区间（最多 6），修脸使用 6 步；所有序列最终降到 0。`ManualSigmas` 只接收生成后的 `sigmas`，不再附加它不支持的 `steps`、`denoise` 参数。一采模板和缓存保持原始六步序列。

这是网关的增强适配调度，官方固定六步一采的质量结论不能直接应用于它，需另做 GPU 画质、动作和口型对照。原始任务快照不做自动改写。

## 验证

离线回归：在 `comfy_gateway/` 执行 `python -m unittest test_pika_h3_workflows test_refinement_schedule test_dynamic_workflow test_minimax_h3_workflows test_fl2va_frame_roles`。覆盖一采固定采样不可被通用参数改写、增强步数与强度真实改变 Sigma、三种 FL2VA 图片模式、九图三音频组装及 UI 连线。

在线验收使用中性测试图片，在指定节点分别生成小尺寸视频，并检查真实完成历史、视频尺寸、帧数、帧率和音轨。测试仅证明集成链路，不代表高分辨率、长视频或最大参考数量的显存与生成质量验收。结果保存在本机 `logs/pika-h3-flex-acceptance/`。
