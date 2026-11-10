# 视频渲染的二采放大与人脸修复开关

REF2VA 普通和接续渲染可独立选择二采放大和人脸修复；FL2VA 不提供这两个开关，也不允许提交增强请求。二者都关闭为基础生成，同时开启为“基础生成 → 二采放大 → 人脸修复”。这两个选项独立于四种接续策略，不将放大或修脸称为新的接续方式。

先调用已发布的 `get_video_enhancement_options`，提供当前授权的 dramaId、shotId、providerId、modelCode，读取模型能力。开关直接位于普通、接续和批量渲染配置中，没有独立页面或预设管理。使用基础 H3 模型，不用固定 face-refine 模型冒充可选修脸；历史任务仍按原快照执行。

需要预览时调用 `preview_video_enhancements`，带 size 和 selection：

```json
{
  "options": {
    "upscale": true,
    "faceRepair": true,
    "scale": 2,
    "upscaleDenoise": 0.25,
    "faceDenoise": 0.4
  }
}
```

两个开关默认关闭。不要提供 presetId/version 或引导用户创建预设。重绘强度属于增强阶段，不能与接续彩噪或 latent 重绘幅度混用。

按原参考素材准备流程就绪、且创作者已授权生成后，把 selection 放入 `render_shot_video` 或 `render_shot_continuation` 的可选 `dynamicWorkflow` 字段。不提交 dynamicWorkflowPlan、dynamicWorkflowStage 或内部节点接线。任务 ID 只表示已提交，不能宣称视频生成成功。

第一版放大固定宽高各两倍；输出像素预算和兼容阶段读取能力。首尾帧基础模型不提供二采放大或人脸修复，不能静默切模型或关闭用户指定的模块。二采会增加耗时，也可能改变外观或动作细节，不保证自动修复劣化、口型或接缝。

自定义二采使用 `upscaleEngine=FREYJA`，可设 `upscaleOutput=FULL_HD`、`upscaleSteps=2`、`upscaleMemory=TEMPORAL`（原生56帧窗口/22帧重叠）、`SPATIAL`（FREYJA 空间分块，实验性）或 `FULL`。空间/时间二者互斥，参数来自 `upscaleTiling`。`upscaleMethod=LATENT_BICUBIC` 不需要额外权重；`H3_LEARNED` 需要显式选择已安装的兼容 H3 latent 放大模型文件 `upscaleLatentModel`，不得猜测文件名或使用 LTX 权重代替。不依赖 TimelineDirector、SelfLift 或 SpatialTiles 插件。历史任务缺少新字段仍按 PIXEL/DOUBLE 执行；旧外部分块已移除，需显式重选后提交，不能暗中迁移。

FULL_HD 要求近16:9/9:16；横屏1920×1088采样后裁为1920×1080，竖屏相反，若修脸开启则在修脸后裁切。当前是完成一采后的独立潜空间二采，并非同一采样进程6+2；源视频仍需编码一次。少高清步数主要降低耗时，分块/时间窗可降低部分显存压力，权重及VAE仍可能OOM；16GB/1080P尚无真实GPU验证，不能承诺画质、显存或无接缝。

接续交接仍使用一采基础视频或原始 latent，增强结果用于最终输出。用户修改增强设置时，可显式选择 reusePrevious 或同镜头的 reuseFromTaskId，系统只复用输入完全一致的成功步骤；不能把其他镜头的任务 ID 用作来源。

使用 `get_shot_continuation_render` 的 workflowSteps 查看各阶段状态及已有产物。超时或结果不明时先查原任务；上游已完成但归档失败时恢复归档，增强失败时恢复原任务的处理阶段，不盲目重新提交一采。静态能力登记和离线测试不证明算力节点在线或真实画质已验证。

渲染中心和任务中心显示片段序号、基础生成/二采/修脸阶段，以及 ComfyUI 提供的当前节点步数。总体百分比按阶段估算；没有实际步数时明确标为估算，不能据此推断成功、精确剩余时间或硬件性能。只有任务实际成功才显示 100%。

接续切换渲染模式时，FL2VA 每段使用自己的 firstFrameImageUrl / lastFrameImageUrl；REF2VA 使用 referenceImages / referenceVideos / referenceAudios 和可选 ControlNet。两种模式的编辑数据可同时保留，但当前任务只处理所选模式的素材，不把隐藏的参考素材带入 FL2VA，也不把首尾帧带入 REF2VA。
