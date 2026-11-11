# SelfLift 二采

渲染配置及片段重跑的「二采方式」提供标准二采与 SelfLift 二采，默认标准。对应字段为 `dynamicWorkflow.options.upscaleSampler`，值为 `STANDARD` / `SELFLIFT`；历史快照缺少该字段时仍为标准二采。

两种方式均使用配置的专用 H3 latent 放大权重。SelfLift-zero 在已完成的一采与高清重绘之间，构造学习式直接放大 latent 和像素放大后 VAE 重编码的成对结果，按残差识别高风险位置并作一致性修正，再进入模型原有的低噪声二采调度。实现于 `FreyjaH3SelfLift`，依据 [SelfLift 论文](https://arxiv.org/abs/2609.02036) 的一致性提升机制；不依赖外部 SelfLift 插件，不减少或合并一采步骤。

SelfLift 增加一次 VAE 解码/重编码。H3 VAE 负责完整时间轴的编码，相邻像素帧不会独立编码而改变 17n+5 时间相位。原始一采 latent 和音频保持不变，音轨仍来自本段基础视频。二采前缀接续在 SelfLift 修正之后挂载，以免覆盖前序锁定区域；保存二采 latent 后才进行修脸。

支持当前配置的 8 个 H3 视频生成型号：`minimax-h3-fl2va`、`minimax-h3-fl2va-turbo4`、`minimax-h3-fl2va-turbo8`、`pika-h3-flex-fl2va`、`minimax-h3-ref2va`、`minimax-h3-ref`、`minimax-h3-ref-turbo8`、`pika-h3-flex-ref2va`。图像生成、超分后处理和补帧模型不是二采底模，不进入此能力目录。能力由显式 handler 和节点绑定声明，不依赖型号名称猜测。

部署须同步更新 Java 后端、前端、FastAPI 网关和 `custom_nodes/freyja_h3_continuation`，队列空闲后重启 ComfyUI。新版网关的 `freyjaUpscale.samplers` 返回两种方式；旧网关不显示可用的 SelfLift 选择，后端拒绝向旧网关提交该方式。缺少节点包时 ComfyUI 会拒绝工作流，不会回退为标准二采。

自动化测试核对模型适配、引用顺序、接续遮罩顺序、恢复与修脸来源。实机 GPU 画质、显存及重影改善尚需单独验收，不能将离线组图通过视为画质结论。
