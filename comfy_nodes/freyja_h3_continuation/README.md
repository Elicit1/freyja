# FREYJA H3 接续节点包

将整个 `freyja_h3_continuation` 文件夹放入算力节点的
`ComfyUI/custom_nodes/`。在该 ComfyUI 的 Python 环境中安装本目录
`requirements.txt`（通常已经有 safetensors）。空闲时重启 ComfyUI。
无需安装 Director、Geiru 或 Context Loop 的整套插件/UI。

节点：`FreyjaH3ChromaNoise`、`FreyjaH3Continue`、`FreyjaH3Restore`、
`FreyjaH3SaveLatent`。可在 `/object_info` 中只读确认是否加载成功。

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
