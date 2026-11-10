# 动态工作流

入口：视频中心 → 动态工作流（`/production/dynamic-workflows`）。基础生成、二采放大和人脸修复拆成独立执行阶段。两个增强开关独立；同时开启时先放大再修脸。

## 执行与产物

| 二采放大 | 人脸修复 | 执行顺序 |
| --- | --- | --- |
| 关 | 关 | BASE |
| 开 | 关 | BASE → UPSCALE |
| 关 | 开 | BASE → FACE |
| 开 | 开 | BASE → UPSCALE → FACE |

每个阶段是独立的 ComfyUI prompt，有自己的执行 ID、上游 prompt/node ID、输入摘要、状态和 MinIO 视频结果。网关只保留当前阶段输出所依赖的节点；放大、修脸阶段不会重新执行基础采样器，也不会保留其他隐藏保存分支。

接续仍按既有四种模式运行。上一段基础视频/原始 AV latent 供下一段交接，增强产物只用于最终裁切拼接。保护模式使用恢复后的基础 latent。原音频沿用来源视频，增强阶段不以新生成音频替换。

二采放大：基础画面 → ImageScale → VAE 编码注入 AV latent → H3 二次采样 → 解码。第一版固定宽高各 2 倍；总像素上限 4,194,304，宽高按 32 对齐，denoise 范围 `(0,0.6]`，默认 0.25。采样步数、采样器与底模/LoRA 组合沿用基础模型，避免任意覆盖蒸馏模型的采样配置。

人脸修复：跟踪裁剪 → 编码注入 → 逐帧重绘 → 解码贴回。默认 denoise 为 0.4，范围 `(0,0.6]`。复用已有 H3 FaceRefine 节点，引用素材沿用原顺序，不增加编号的音频或视频引用。输出前核验帧数和尺寸。

### FREYJA 自定义二采（组件 v1）

新建预设默认使用 FREYJA 潜空间二采、1080P、2 步高清重绘和原生时间分窗。旧预设缺少新字段时继续使用原有像素二采、宽高两倍；旧第三方分块选项若曾开启，会明确要求重新选择组件并保存新版本，不静默改变执行语义。

执行：源视频按原尺寸 VAE 编码 → 潜空间放大 → 选定步数的 H3 高清重绘 → 解码 → 最后一个增强阶段裁切输出。该实现是完成一采后的独立增强，保留分阶段持久化、复用和恢复；不是 TimelineDirector 的同一采样进程 6+2，也没有减少一采原来的步数。独立阶段目前需要对归档源视频重新编码一次；不能声称全流程无 VAE 往返。

- `upscaleEngine`: `FREYJA`；旧 `PIXEL` 用于兼容已有预设。
- `upscaleMethod`: `LATENT_BICUBIC`（FREYJA 在 CPU 逐 latent 时间位置插值，不改变时间轴，不需附加权重），或 `H3_LEARNED`（ComfyUI 原生 `LatentUpscaleModelLoader`、`MiniMaxH3LatentUpscaleWithModel`，需要在 `upscaleLatentModel` 显式填入已安装的兼容 H3 权重文件名）。不根据文件名猜测模型架构，不自动下载权重，不能使用 LTX 放大权重冒充 H3。
- `upscaleOutput`: `FULL_HD` 或 `DOUBLE`。FULL_HD 要求近 16:9/9:16，横屏采样 1920×1088、成片 1920×1080；竖屏采样 1088×1920、成片 1080×1920。若后面还有修脸，放大阶段保留完整采样尺寸，最后修脸阶段才裁切，Take 记录真实成片尺寸。
- `upscaleSteps`: 默认 2，至少 1 且不能超过模型登记的采样步数。沿用模型的 sampler 和 scheduler；该参数只改变二采，少步数不保证更低的峰值显存或更好画质。
- `upscaleMemory`: `TEMPORAL`（ComfyUI 原生 `MiniMaxH3ContextWindows`，默认 56 帧窗口/22 帧重叠，关闭 FreeNoise 重排）、`SPATIAL`（FREYJA 自定义二维空间块，实验性）或 `FULL`。第一版空间分块与时间分窗互斥，不能同时开启。

空间分块参数沿用 `upscaleTiling.tileWidth/tileHeight/overlap`：默认 768/512/192 像素。宽高 256–2048、重叠 32–512，均为 32 倍数，重叠小于块宽高。它在每次模型前向处理重叠 latent 区域并融合预测，不是独立生成每块成品再拼接。保持全局位置编码、完整参考和音频；融合累加器在 CPU。准备采样时按块预算计入完整 AV 缓冲和参考条件。不支持 ControlNet 或其他模块抢占 mask；该组件只接独立增强模型，不改变接续 BASE 的遮罩。分块仍可能有接缝、外观改变或 OOM；16GB 尚未做 GPU 验证。

学习式放大使用 ComfyUI 核心的模型加载、预算与卸载机制，FREYJA 不复制训练权重或自动修改 ComfyUI 全局启动参数。VAE 编码/解码和模型权重仍有峰值需求；不能把分块预算理解为必定能装入 16GB。图像全程按当前片段处理，不累积整条接续视频的高清帧。

实现参考 Songssx/ComfyUI-MiniMaxH3-TimelineDirector 提交 `050226d37d25e20d878b47bdf92c8f912afccdaa` 的 latent 放大、空间预测融合、CPU 累加与预算思路；空间 PackedLayout 适配注明 GPL-3.0 来源，随 FREYJA 组合节点包按 GPL-3.0 分发。运行时不依赖 TimelineDirector、SelfLift 或 SpatialTiles 插件。

示例（options）：

```json
{
  "upscale": true,
  "faceRepair": false,
  "scale": 2,
  "upscaleDenoise": 0.25,
  "upscaleEngine": "FREYJA",
  "upscaleMethod": "LATENT_BICUBIC",
  "upscaleOutput": "FULL_HD",
  "upscaleSteps": 2,
  "upscaleMemory": "TEMPORAL",
  "upscaleTiling": {"windowFrames": 56, "overlapFrames": 22}
}
```

组件参数进入 UPSCALE 输入摘要，改动二采不影响 BASE 的复用。最终裁切也进入实际最后阶段的摘要。

当前首尾帧模型支持基础生成和二采放大；人脸修复暂要求参考生成基础模型，由能力接口明确返回。全局动作 ControlNet 不直接注入裁剪修脸分支。模型重绘可能改变动作细节，离线接线检查不等于画质或口型已验证。

## 预设、版本与任务参数

`dynamic_workflow_preset` 保存预设，`dynamic_workflow_version` 保存每次版本快照。修改、删除使用乐观版本检查；已经排队或执行的任务保存解析后的完整计划，不随预设后续修改而改变。

单镜提交和批量模式配置均可带 `dynamicWorkflow`：

```json
{
  "dynamicWorkflow": {
    "presetId": "2032095628944588803",
    "version": 3,
    "options": {
      "upscale": true,
      "faceRepair": true,
      "scale": 2,
      "upscaleDenoise": 0.25,
      "faceDenoise": 0.4
    },
    "reusePrevious": true
  }
}
```

省略 `presetId/version` 是本次自定义；选预设而省略 `options` 则读取该版本默认值。ID 全程保持字符串。`dynamicWorkflowPlan`、`dynamicWorkflowStage` 只允许服务端生成，客户端不能提交。

选择 `reusePrevious=true` 时服务端冻结本镜头最近具有已完成动态步骤的任务；也可显式指定 `reuseFromTaskId`。只复用同镜头、阶段输入摘要完全一致的成功结果。改变修脸参数时，一采与放大可复用；改变放大参数会重做放大及后续修脸；改变基础生成输入会使相关结果失效。不指定复用时，新任务正常执行。

## API 与 MCP

| 接口 | 用途 |
| --- | --- |
| `GET /production/dynamic-workflows` | 按可选 providerId/modelId 查询预设 |
| `POST /production/dynamic-workflows` | 创建或按 id/version 保存新版本 |
| `DELETE /production/dynamic-workflows/{id}?version=...` | 乐观版本删除 |
| `GET /production/dynamic-workflows/capabilities?providerId=...` | 读取网关登记的模块能力，不探测 GPU |
| `POST /production/dynamic-workflows/preview` | 解析 providerId/modelId/size/selection，返回有效计划 |
| `GET /production/dynamic-workflows/tasks/{taskId}/steps` | 查询阶段状态及产物 |

网关通过 `GET /v1/dynamic-workflows/capabilities` 提供静态登记能力和模块 revision；实际提交时验证精确模型/节点、revision，以及所选阶段依赖的已加载节点。不使用人工验收标记。关闭增强不要求增强节点已安装。

MCP 使用已发布能力平台注册的 `get_dynamic_workflow_options`、`preview_dynamic_workflow` 查询/预览；`render_shot_video`、`render_shot_continuation` 接受可选 `dynamicWorkflow`。生成仍需创作者授权，并遵循原素材准备和幂等要求。`get_shot_continuation_render` 返回工作流阶段状态；不能通过原始工具注册表绕过发布状态或项目权限。

## 失败恢复

`dynamic_workflow_step` 的 PREPARING/ SUBMITTING/ SUCCESS/ FAILED 状态先落库后提交。持久化失败直接报错。上游身份在收到异步确认后立即保存。

- 前置准备失败、明确拒绝：可以重试该阶段。
- 已提交或结果不明：查询原 execution/prompt/node，未确认结束时不重复提交。
- 上游已成功、视频归档失败：重新取回并归档该结果，不重跑采样。
- 某增强失败：任务中心展开步骤查看已有产物，点击“继续工作流”恢复。已完成阶段保留。
- 取消：定位当前阶段真实 execution，沿用既有取消与中断机制。

旧任务未提供 dynamicWorkflow 时保持原模板行为；固定 FaceRefine 模板保留用于历史兼容。新制作入口使用基础模型和可选模块。

## 安装与验证边界

更新 Java、前端与网关代码。数据库启动时幂等新增三张表及步骤来源列，不重写历史 JSON。

算力节点更新 `comfy_nodes/freyja_h3_continuation`，新增 `FreyjaH3CheckFrames`；安装方式继续使用 `scripts/install_h3_continuation.py --comfy-root ...`，脚本不会重启 ComfyUI。放大还需现有 `H3InjectVideoLatent`，修脸需 H3 FaceRefine 的裁剪、逐帧重绘和贴回节点。代码仅在用户选择对应阶段时连接它们。

自定义二采需更新 FREYJA 节点包，加载 `FreyjaH3UpscaleEncode`、`FreyjaH3LatentResize`、`FreyjaH3TileModel`、`FreyjaH3FinishSize`。原生时间窗需当前 ComfyUI；选择学习式放大时才需要原生 latent 模型加载节点和兼容权重，权重放在 `models/latent_upscale_models/`。不再安装第三方 SpatialTiles；若以前安装过也无须由 FREYJA 自动卸载，当前图不会引用它。静态能力仅表示网关支持，不证明算力节点在线或依赖已安装。

本次不提交视频渲染任务，不探测或重启离线算力节点。验证覆盖阶段组装、引用契约、失败恢复、复用、版本、MCP、前端构建及四套主题模拟交互；真实画质、显存和接缝效果仍需节点恢复后评估。
