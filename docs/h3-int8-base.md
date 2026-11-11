# MiniMax H3 INT8 基础工作流

新增两个独立型号，不修改原有 Fast、Turbo 或 Pika 型号及历史任务快照。

| 模型编码 | Studio 模型类型 | 最大参考图 / 音频 / 视频 | ComfyUI 工作流 |
| --- | --- | --- | --- |
| `minimax-h3-fl2va-int8` | `TXT2VIDEO_FIRST_LAST` | 2 / 0 / 0 | [FL2VA UI 图](../comfyUI_workflow/video_minimax_h3_fl2va_int8.json) |
| `minimax-h3-ref2va-int8` | `TXT2VIDEO_REF` | 9 / 3 / 3 | [REF2VA UI 图](../comfyUI_workflow/video_minimax_h3_ref2va_int8.json) |

API 模板分别为 [FL2VA](../comfy_gateway/comfyUIApi/minimax_h3_fl2va_int8.json) 和 [REF2VA](../comfy_gateway/comfyUIApi/minimax_h3_ref2va_int8.json)。UI 图可直接导入 ComfyUI；示例图片位于 `comfyUI_workflow/examples/`，导入其他机器后需上传示例图或选择自己的图片。默认 832×480、124 帧、24 FPS。FL2VA 可断开尾帧做首帧生成，断开全部图片做文生视频。

## 权重与采样

- FL2VA 底模：`minimax_h3_fl2va_pruned_int8_convrot.safetensors`。
- REF2VA 底模：`minimax_h3_ref2va_pruned_int8_convrot.safetensors`。
- 文本编码器：`qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors`。
- 视频 VAE：`minimax_h3_video_vae_int8_convrot.safetensors`。
- 音频 VAE：`minimax_h3_audio_vae_fp32.safetensors`。

底模、文本编码器和 VAE 分别放在 ComfyUI 的 `models/diffusion_models/`、`models/text_encoders/`、`models/vae/` 中。INT8 指视频扩散底模权重；编码器与音频 VAE 使用以上各自精度。

底模从 `UNETLoader` 直接连接 `BasicGuider` 和 `BasicScheduler`，使用 `res_multistep` + `simple`、默认 **20 步**、`denoise=1`。不加载任何 LoRA，也不包含 EasyCache、Sol Attention、Fused Modulation、Chunk FeedForward 或 LCM/固定少步 Sigma 调度。网关映射 `steps`，调用方可显式调整基础步数。

## 网关与 Studio

两个型号已登记于 `comfy_gateway/nodes/machine_default.yaml`。当前开发环境的私有 `machine_local.yaml` 也已同步；部署其他私有节点时，要将两个型号的完整配置合并到对应文件，因为同名节点会覆盖整份模型列表。启动或重启网关后，`GET /v1/models` 应返回两个编码。

Studio 继续使用模型中心、统一任务中心和网关实时能力目录，无需增加前端型号白名单。两个型号分别出现在首尾帧模式、多参考模式的模型下拉框中。当前开发库已登记；其他环境可以通过已运行的 Studio 后端注册：

```powershell
$env:FREYJA_API_TOKEN = "当前Studio登录Token"
python scripts/register_h3_int8_models.py --backend http://127.0.0.1:8080 --provider-id "已有网关提供商ID"
```

注册使用已有模型中心 API 生成业务 ID、持久化并清理缓存；相同提供商下已存在的型号保持原样。提供商 ID 始终作为字符串传递。

## 二采与人脸修复

两种模式均声明 `BASE`、`UPSCALE`、`FACE` 能力，通过现有 `dynamic_workflow` 编排执行。增强阶段继续使用同一 INT8 底模、编码器和 VAE，不添加加速 LoRA。

- 二采支持现有原生 latent 输入、学习式潜空间放大、STANDARD / SELFLIFT、FULL / TEMPORAL / SPATIAL 内存模式，以及二采段间 latent 接续。二采步数由独立的增强设置决定，上限 20 步；默认增强设置沿用平台配置。
- 人脸修复继承基础型号的 20 步、`res_multistep` 和 `simple` 调度，并按请求的 `denoise` 重绘。FL2VA 保留首尾帧角色；REF2VA 保留有序图片、音频、视频参考。输出保留源视频音轨。
- 分块或时间窗只在创作者开启对应增强选项时动态加入；基础图没有这些处理节点。算力端须具备当前 FREYJA H3 扩展和对应人脸节点，能力预检会检查缺失节点。

## 验证与维护

在 `comfy_gateway/` 执行：

```powershell
python -m unittest test_h3_int8_workflows test_dynamic_workflow test_video_reference test_minimax_h3_workflows test_pika_h3_workflows test_refinement_schedule test_h3_control_compatibility test_fl2va_frame_roles
```

覆盖默认20步、显式步数、无加速节点、首尾帧与多参考绑定、二采与修脸单独采样、图的无环性、UI 接线及本地覆盖配置。现有动态工作流与参考视频回归也包含新增型号。

如需重新生成四份 JSON，执行 `python comfy_gateway/build_h3_int8_workflows.py`。生成器复用现有 H3 模板的权重和节点结构，移除 LoRA 并直接连接底模，UI 图同步使用标准20步调度。

开发环境本次完成离线回归与模型中心登记。远端 ComfyUI 连接超时，尚未完成真实 GPU 出片、二采和人脸修复的在线验收。
