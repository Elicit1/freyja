# FREYJA H3 接续节点包

将整个 `freyja_h3_continuation` 文件夹放入算力节点的
`ComfyUI/custom_nodes/`。在该 ComfyUI 的 Python 环境中安装本目录
`requirements.txt`（通常已经有 safetensors）。空闲时重启 ComfyUI。
无需安装 Director、Geiru 或 Context Loop 的整套插件/UI。

节点：`FreyjaH3ChromaNoise`、`FreyjaH3Continue`、`FreyjaH3Restore`、
`FreyjaH3SaveLatent`、`FreyjaH3CheckFrames`。可在 `/object_info` 中只读确认是否加载成功。

动态工作流的二采放大、人脸修复为独立可选阶段。`FreyjaH3CheckFrames` 在采样前
检查源帧数，输出前核对帧数与尺寸。历史像素二采复用 `ImageScale` 和现有
`H3InjectVideoLatent`；修脸复用 H3 FaceRefine 的跟踪、逐帧重绘和贴回节点。
自定义二采由本包的 `FreyjaH3UpscaleEncode`、`FreyjaH3LoadLatent`、`FreyjaH3TileModel`、`FreyjaH3FinishSize` 提供，不依赖第三方 SpatialTiles、TimelineDirector 或 SelfLift 插件。内置 latent 插值已移除；二采使用 ComfyUI 核心 `LatentUpscaleModelLoader`、`MiniMaxH3LatentUpscaleWithModel` 和网关配置的专用权重，默认 `minimax_h3_latent_upscaler_3d_conv_v1_fp16.safetensors`。开启放大即使用默认模型，不需要在界面选择或逐次在线校验。历史片段二采通过 `FreyjaH3LoadLatent` 直接读取一采原始 AV latent，校验兼容性、尺寸和帧数；没有 latent 的旧任务才从来源尺寸视频编码。高清采样后裁成精确1080P，仍为独立采样阶段。
空间分块是实验性的模型前向预测融合，CPU累加，保留全局位置编码、完整参考及音频，并调整准备采样预算；不支持ControlNet或外部mask冲突。可另选原生时间分窗，二者互斥。模型、VAE、完整latent和参考仍占显存，不能保证16GB无OOM。
未选择增强时不需要这些增强节点。更新本包后需在算力队列空闲时重启加载。

若历史重跑报 `DYNAMIC_NODES_MISSING: FreyjaH3LoadLatent`，说明运行中的节点包未包含 latent 读取节点。更新整个目录并重启后，确认 `/object_info` 中包含 `FreyjaH3LoadLatent` 再重跑。仅更新网关或前端无法补齐 ComfyUI 节点；专用放大权重需预先放在 `models/latent_upscale_models/`，文件名应与网关配置一致。

需要支持 H3 原生 AV noise mask、`set_model_denoise_mask_function` 及
`WrappersMP.APPLY_MODEL` 的当前 ComfyUI。旧版本或其他 mask 适配冲突会明确报错。
不改采样步数、Turbo LoRA、sampler、scheduler 和 sigma shift。

两个 latent 模式当前使用模板主采样器的一采 AV latent（不使用人脸修复后的
像素重新编码替代），与当前 17k+5 帧网格相位对齐。画布、底模、VAE、LoRA
及调度配置变化时不允许复用不兼容产物。所有节点在 CPU 上可以做小 tensor 测试；
节点本身不提交任务，也没有自动连网下载模型行为。

限制：两个 latent 模式暂不与片段内 context windows 同用；可与 ControlNet
一起使用，但可选延迟尾锚暂不与 ControlNet 同用。默认使用干净首帧锚定，关闭尾锚。
标准/彩噪模式保留现有窗口能力。视觉加噪不污染动作控制视频或音频。

`FreyjaH3SaveLatent` 在输出目录原子写入 safetensors，history 返回
`ui.latents` 文件引用。网关通过精确 prompt/node 获取该文件，Java 以摘要地址
归档到 MinIO。下轮使用有时效的服务端签名 URL，经网关摘要校验后上传。
`REFERENCE_MEDIA_HOSTS` 需包含签名 URL 使用的 MinIO 主机名。

归档预算为单文件 2 GiB；依赖原始 latent 的段在视频与交接产物持久化完成后
才标记成功。上传失败可恢复归档，不自动重新生成。

本次验证不包含真实视频渲染。接续画质需在创作者自己的动作与模型组合中评估。

UI 设计参考：[FREYJA 多模式接续设计项目](https://stitch.withgoogle.com/projects/16195879025110818815)。
许可证与来源见 `LICENSE` 和 `NOTICE.md`。
