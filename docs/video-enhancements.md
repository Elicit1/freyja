# 视频渲染增强：二采放大与人脸修复

入口为 REF2VA 普通视频渲染、接续视频渲染及批量参考模式中的「二采放大」「人脸修复」两个独立开关，默认关闭。提交时系统按固定顺序拼接所选功能：基础生成 → 可选二采放大 → 可选人脸修复。没有独立管理页面、工作流预设或流程编辑器。

## 执行与产物

| 二采放大 | 人脸修复 | 执行顺序 |
| --- | --- | --- |
| 关 | 关 | BASE |
| 开 | 关 | BASE → UPSCALE |
| 关 | 开 | BASE → FACE |
| 开 | 开 | BASE → UPSCALE → FACE |

每个阶段是独立的 ComfyUI prompt，有自己的执行 ID、上游 prompt/node ID、输入摘要、状态和 MinIO 视频结果。网关只保留当前阶段输出所依赖的节点；放大、修脸阶段不会重新执行基础采样器，也不会保留其他隐藏保存分支。

接续一采仍按既有四种模式运行，上一段基础视频/原始 AV latent 供下一段一采交接。新任务开启 FREYJA 二采时，上一段二采 latent 另行交给下一段二采；两条链分别继承。保护模式使用恢复后的基础 latent。原音频沿用来源视频，增强阶段不以新生成音频替换。

二采放大：基础画面 → ImageScale → VAE 编码注入 AV latent → H3 二次采样 → 解码。第一版固定宽高各 2 倍；总像素上限 4,194,304，宽高按 32 对齐，denoise 范围 `(0,0.6]`，默认 0.25。采样步数、采样器与底模/LoRA 组合沿用基础模型，避免任意覆盖蒸馏模型的采样配置。

人脸修复：跟踪裁剪 → 编码注入 → 逐帧重绘 → 解码贴回。默认 denoise 为 0.4，范围 `(0,0.6]`。复用已有 H3 FaceRefine 节点，引用素材沿用原顺序，不增加编号的音频或视频引用。输出前核验帧数和尺寸。

### FREYJA 自定义二采（组件 v1）

新开启二采默认使用 FREYJA 潜空间二采、1080P、2 步高清重绘和原生时间分窗。历史任务缺少新字段时继续使用原有像素二采、宽高两倍；旧第三方分块选项若曾开启，会明确要求重新选择二采方式后重新提交，不静默改变执行语义。

执行：兼容的一采原始 AV latent → 潜空间放大 → 选定步数的 H3 高清重绘 → 解码 → 最后一个增强阶段裁切输出。新建 FREYJA 接续增强任务会保存每段的一采 latent；使用 `FreyjaH3LoadLatent` 校验模型、VAE、LoRA、调度器、来源画布和帧数后直接加载，不再从视频重新 VAE 编码。原视频只提供原声轨。单镜或旧任务没有可用 latent 时保留原尺寸 VAE 编码路径；旧任务快照不被静默改写。

这仍是完成一采后的独立增强，保留分阶段持久化、复用和恢复；不是 TimelineDirector 的同一采样进程 6+2，也没有减少一采原来的步数。原生输入的摘要包含 latent SHA-256 和来源尺寸，不包含有时效的签名 URL。

### 二采 latent 接续

网关静态能力 `freyjaUpscale.secondPassContinuity` 驱动新计划的 `secondPassContinuity` 标记；不增加界面开关，不逐次探测放大模型。历史快照缺少标记时沿用独立二采，不改写历史 JSON。

每段二采将裁切前的完整 AV latent 归档为 `SECOND_PASS`，在 `dynamic_workflow_step.result_manifest_json` 保存引用。后续二采传入 `save_latent=true` 和 `latent_context={url,content_sha256,frames}`。上一段二采尾部在当前段潜空间放大后注入，重叠区域 mask 固定为零；恢复后的同一份 latent 供解码和下段交接。音频不继承上一段二采，修脸结果也不作为二采上下文。真实重叠帧数来自接续计划，支持 17k+5 网格与不同长度的末段。

兼容性校验区分一采/二采，校验底模、VAE、LoRA 与完整采样画布；阶段输入摘要还包含前序二采 SHA-256 和重叠帧数，排除临时签名 URL。二采参数变化可重新采样，时间分窗、空间分块和完整处理共用同一接续方式。

二采视频生成成功而 latent 归档失败时进入 `ARCHIVING`；恢复只补归档，不重复采样。成功步骤复用时一并复用二采清单。局部重做使用未选前驱的已存二采 latent，连续选择多段则使用刚重做的前驱；未选片段保持既有成片。旧任务缺少前驱二采 latent 时须从首段连续选择重跑二采，不能拿一采或视频编码冒充。

- `upscaleEngine`: `FREYJA`；旧 `PIXEL` 用于兼容已有任务。
- `upscaleMethod`: 新二采统一为 `H3_LEARNED`，移除「内置放大」及模型选择器。开启二采后直接使用网关 `ModelConfig.default_upscale_model` 配置的专用 H3 权重，默认文件为 `minimax_h3_latent_upscaler_3d_conv_v1_fp16.safetensors`（名称来自已保存的节点清单），可在节点 YAML 的具体模型下通过 `default_upscale_model` 覆盖。Java 从静态增强能力中的 `defaultLatentModel` 将精确文件名冻结进新任务；旧快照已指定的模型继续透传。无需前端选择模型或读取模型列表，也不在提交二采时通过 `/object_info` 预检模型是否安装；缺失、不兼容或加载失败由 ComfyUI 提交/执行结果报告。只保留参数、路由和快照版本校验，旧内置插值请求明确拒绝，不静默替换运行中的历史任务。
- `upscaleOutput`: `FULL_HD` 或 `DOUBLE`。FULL_HD 要求近 16:9/9:16，横屏采样 1920×1088、成片 1920×1080；竖屏采样 1088×1920、成片 1080×1920。若后面还有修脸，放大阶段保留完整采样尺寸，最后修脸阶段才裁切，Take 记录真实成片尺寸。
- `upscaleSteps`: 默认 2，至少 1 且不能超过模型登记的采样步数。沿用模型的 sampler 和 scheduler；该参数只改变二采，少步数不保证更低的峰值显存或更好画质。
- `upscaleMemory`: `TEMPORAL`（ComfyUI 原生 `MiniMaxH3ContextWindows`，默认 56 帧窗口/22 帧重叠，关闭 FreeNoise 重排）、`SPATIAL`（FREYJA 自定义二维空间块，实验性）或 `FULL`。第一版空间分块与时间分窗互斥，不能同时开启。

界面在每个显存选项旁展示用途和代价，窗口、分块大小收在「高级显存参数」中，默认不展开：

| 选项 | 适用情况 | 代价 |
| --- | --- | --- |
| 按时间分窗（推荐） | 较长视频，一次处理较短的时间范围 | 可能影响运动连续性 |
| 按画面分块（实验） | 高分辨率时显存不足，一次处理画面的一部分 | 可能更慢或出现接缝 |
| 完整处理 | 显存充足，或用于对比排查分窗、分块问题 | 整个片段与画面同时处理，显存压力最大 |

这些是处理方式，不是显存容量保证；模型权重、分辨率和视频长度仍决定实际峰值。保持原来的默认值，不自动改变已保存任务的执行方式。

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
  "upscaleMethod": "H3_LEARNED",
  "upscaleOutput": "FULL_HD",
  "upscaleSteps": 2,
  "upscaleMemory": "TEMPORAL",
  "upscaleTiling": {"windowFrames": 56, "overlapFrames": 22}
}
```

组件参数进入 UPSCALE 输入摘要，改动二采不影响 BASE 的复用。最终裁切也进入实际最后阶段的摘要。

### 多片段二采替换与重新合成

视频历史和任务处理步骤提供「片段二采替换」。弹窗支持逐段勾选、全选可用片段、清空、已选清单与逐段原视频预览；提交后保留已提交清单。二采参数统一应用到所选片段，各段原有的人脸修复开关与强度保持不变。仅允许来源任务成功、所有片段的一采及最终增强产物完整、目标片段已有原始一采 latent 时提交。当前只替换增强结果；不重生成一采，不改变后续片段使用的接续上下文、片段边界或完整视频输出尺寸。

- `GET /production/video-enhancements/tasks/{taskId}/segments`：返回可替换的片段、各段原有参数、原视频预览及禁用原因。
- `POST /production/video-enhancements/tasks/{taskId}/segments/{segmentId}/upscale`：提交 `{requestId, options}`。`requestId` 为 8–80 位字母、数字、连字符或下划线，同一请求重试返回同一任务；同标识内容变化报错。
- `POST /production/video-enhancements/tasks/{taskId}/segments/upscale`：提交 `{requestId, segmentIds, options}`，`segmentIds` 为非空、不重复的字符串数组。一次只创建一个任务，按原时间顺序重跑所选片段并合成一个新版本。同一组片段调整勾选顺序后重试仍返回原任务；空选、重复、外来片段或任一选中片段缺少原始数据时整单拒绝。原单片段接口和历史任务快照仍可读取。
- 新任务冻结来源片段 URL、原始 latent 清单和原裁切计划。复用所有一采及未选中片段的增强结果，只重跑选中片段的二采和其后已开启的人脸修复，再按原时间轴拼接，保存为新的完整 Take。
- 历史二采重新提交时，选中片段的已归档参考视频先按当前能力契约校验，再在新快照中绑定当前参考视频工作流版本；不重新下载源素材或改写历史快照。素材超出当前限制、模型不可用或参考视频与二采路由不一致时拒绝提交。旧失败任务仍保留原快照，升级后应从成功的视频历史重新提交片段二采。
- 原任务、Take 和产物保持不变；新版本成功后可以再次作为替换来源。失败或上游结果不明时走已有任务恢复机制，不因重新点击而重复采样。
- 输出尺寸变化、片段不属于来源任务、缺失 latent、缺失其他片段产物或分镜已有活动任务时明确拒绝。模型与参考素材继续执行原有快照检查；不允许通过该接口更换模型或修改剧情输入。

### 重影排查记录

2026-11-10 对任务 `RENDER_2119993933123670018` 的第一个片段在 2.5 秒处抽帧对比：二采相对一采新增树枝、花丛及人物边缘双轮廓，静态背景也受影响。该任务实际使用视频重新编码、LATENT_BICUBIC、2 步、denoise 0.25、56/22 帧时间窗，人脸修复关闭。可定位到二采路径，但这一对比不能进一步证明是 VAE 往返、latent 插值还是窗口预测融合单独造成。

原始 latent 直入去掉了已确认的多余编码环节，但尚不能据此认定画质问题已修复。

2026-11-10 创作者追加实测反馈：改为「完整处理」后，重影与此前相同；降低重绘强度后，重影反而加重。就该次对比而言，关闭分窗/分块没有消除问题，不能继续将显存处理方式作为主要归因，也不能将降低 denoise 当作已验证的修复方案。此次反馈尚未绑定具体任务 ID、放大方式和完整参数快照，不能扩展为所有配置下的普遍结论。

同日创作者进一步反馈：改用专用放大模型后，二采重影得到避免。结合完整处理未改善、降低重绘强度反而加重的反馈，当前优先怀疑内置潜空间插值放大路径；兼容 H3 的专用学习式放大模型是此次实测有效的处理方式。不再要求创作者重复降低重绘强度或切换显存处理方式来修复同一问题。反馈未包含专用模型的精确文件名和完整对照参数，不能据此保证任意模型、素材或配置均无重影。

内置 `FreyjaH3LatentResize` 对各时间位置的 latent 空间维做双三次插值，不混合时间帧，但 latent 插值仍不保证高分辨率解码的图像一致性。降低重绘强度可能保留输入中的伪影，这仍是待验证的机制解释。若继续修复内置路径，应固定来源片段、原始 latent、种子、目标尺寸和 VAE，比较原始 latent 直接解码、放大 latent 不重绘直接解码、放大后正常二采的结果，并与专用学习式放大对照、核对实际 sigma 调度。仅在放大后直接解码已出现新增双轮廓时，才能将问题进一步定位到放大/解码路径。不得将 CPU/组图测试通过描述为已证明重影消失。

2026-11-10 联网核对上游原始资料，确认直接 latent 插值造成重影已有明确记录：

- [LBH-123-AI 模型卡](https://huggingface.co/LBH-123-AI/Minimax_h3_latent_Upscaler#purpose) 明确说明，普通 bilinear/bicubic latent 插值会引入 ghosting / double-image artifacts；专用网络通过配对低/高分辨率 latent 训练，替代该直接插值过程。这与当前 `LATENT_BICUBIC` 实现及创作者的对照反馈吻合。
- [Tr1dae H3 放大节点说明](https://github.com/Tr1dae/ComfyUI-MiniMaxH3_LatentUpscaler#pass-2-artefacts-what-actually-causes-them) 将普通分量插值在内容边缘改变 latent 向量的幅度和分布列为二采伪影原因。其保范数 `bislerp`、最近邻等只是备用路径；不能保证最近邻或单纯恢复全局均值/方差就能修复 H3 画质。
- [SelfLift 论文](https://arxiv.org/abs/2609.02036) 讨论直接 latent 升分辨率带来的分布不匹配和少步采样恢复能力不足；论文实验对象为图像模型，不能直接当成 H3 视频验证。[H3 适配实现](https://github.com/facok/comfyui-SelfLift#diagnosing-h3) 公开了单一种子的对比：直接最近邻仍有伪影，纯像素/VAE 锚点及较强一致性校正表现较好，并明确标注 H3 适配为实验性。

最终产品调整：移除直接双三次 latent 插值节点和前端入口，所有新二采默认走配置好的专用 H3 学习式放大模型，不新增普通像素放大方案。创作者只需开启放大后提交，无模型选择和逐次在线清单校验；历史任务记录保持可读，内置插值的旧请求不再执行。旧像素二采运行分支仅用于已有快照兼容，新任务准备时统一为专用模型二采。

二采放大与人脸修复均仅开放给 REF2VA 基础模型；FL2VA 普通及接续渲染均不显示开关，后端与网关也拒绝 FL2VA 增强请求。全局动作 ControlNet 不直接注入裁剪修脸分支。模型重绘可能改变动作细节，离线接线检查不等于画质或口型已验证。

Pika REF 使用相同的可选增强阶段。一采保持官方固定六步 `ManualSigmas` + LCM；放大和修脸阶段读取该序列，以 `denoise` 选择曲线末尾区间，并按实际增强步数进行分段线性重采样。放大使用 `upscaleSteps`，修脸使用基础模型登记的六步；结果仍由 `ManualSigmas` 输出，只传 `sigmas`。例如同为 `denoise=0.25`，二采 2 步、4 步分别生成 3、5 个 Sigma 点，起始噪声水平相同，终点为 0。该调度是网关增强适配，尚未完成 GPU 画质对照；不将官方一采的质量结论套用到新调度。

独立人脸修复型号及其三份专用网关模板已删除，默认节点和本机节点 YAML 同步移除登记。基础 REF 模型继续提供可配置的 `FACE` 阶段；未开启时只生成基础视频。旧型号请求明确失败，不改写历史快照，也不匹配到相似基础型号。

## 提交参数与接口

单镜提交和批量模式配置使用现有请求的 `dynamicWorkflow.options` 承载两个开关及增强参数；这个名称只是已有任务的内部兼容协议。用户不创建或选择工作流。两个开关都关闭时不创建增强计划，也不要求增强节点或增强能力接口可用。开启时服务端冻结参数、模型版本及输出尺寸，再按固定顺序执行。

```json
{
  "dynamicWorkflow": {
    "options": {
      "upscale": true,
      "faceRepair": true,
      "upscaleEngine": "FREYJA",
      "upscaleOutput": "FULL_HD",
      "upscaleSteps": 2,
      "upscaleMemory": "TEMPORAL",
      "upscaleDenoise": 0.25,
      "faceDenoise": 0.4
    }
  }
}
```

`dynamicWorkflowPlan`、`dynamicWorkflowStage` 只允许服务端生成。任务阶段仍持久化于 `dynamic_workflow_step`，用于故障恢复和同一执行的结果核对；不新建预设表或版本管理表，也不删除已有历史表或任务数据。

| 接口 | 用途 |
| --- | --- |
| `GET /production/video-enhancements/capabilities?providerId=...` | 读取所选提供商的增强能力 |
| `POST /production/video-enhancements/preview` | 预览开关、基础/输出尺寸和有效参数 |
| `GET /production/video-enhancements/tasks/{taskId}/steps` | 查询处理阶段及产物 |

网关内部保留 `GET /v1/dynamic-workflows/capabilities` 的阶段协议。实际提交检查当前模型、节点和版本；不使用人工验收标记。

MCP 使用已发布能力平台中的 `get_video_enhancement_options`、`preview_video_enhancements`；`render_shot_video` 和 `render_shot_continuation` 接受上述两个开关。工具不提供创建、选择或管理预设的功能。生成仍遵循创作者授权、素材准备和任务幂等要求。

## 渲染进度

网关监听当前 prompt 的 ComfyUI WebSocket 节点与进度事件，记录当前节点类型、节点步数、已执行节点数及更新时间。按 prompt 隔离，其他任务的事件不会更新此任务。遥测缺失不改变真实任务状态。

Java 在等待提交响应和查询上游结果时更新任务进度。接续显示片段 n/N，按基础生成、二采放大、人脸修复分阶段计入总体百分比；实际节点计数原样展示。没有步数或遥测过期时按当前阶段耗时平滑估算，明确显示「总体估算」「等待实际完成」，不伪造步数或预计完成时间。

估算进度保持单调，运行期间最多 99%；仅结果提交成功后置 100%。排队不持续上涨，取消、失败和中断后停止更新。拼接、下载、MinIO 归档及结果保存均显示实际阶段。任务中心、渲染中心及批次抽屉读取同一持久化进度；批次进度包含当前镜头的部分进度，不再只按已完成镜头数跳变。页面重开读取已保存的进度。

## 失败恢复

`dynamic_workflow_step` 的 PREPARING/ SUBMITTING/ ARCHIVING/ SUCCESS/ FAILED 状态持久化。持久化失败直接报错。上游身份在收到异步确认后立即保存。

- 前置准备失败、明确拒绝：可以重试该阶段。
- 已提交或结果不明：查询原 execution/prompt/node，未确认结束时不重复提交。
- 上游已成功、视频归档失败：重新取回并归档该结果，不重跑采样。
- 某增强失败：任务中心展开步骤查看已有产物，点击“继续视频处理”恢复。已完成阶段保留。
- 取消：定位当前阶段真实 execution，沿用既有取消与中断机制。

旧任务未提供 dynamicWorkflow 时保持原模板行为；固定 FaceRefine 模板保留用于历史兼容。渲染使用基础模型和两个增强开关。

## 安装与验证边界

更新 Java 与网关代码。数据库启动时幂等维护处理阶段表、步骤来源列与 latent 归档清单列，不重写历史 JSON。

算力节点更新 `comfy_nodes/freyja_h3_continuation`，新增 `FreyjaH3CheckFrames`；安装方式继续使用 `scripts/install_h3_continuation.py --comfy-root ...`，脚本不会重启 ComfyUI。放大还需现有 `H3InjectVideoLatent`，修脸需 H3 FaceRefine 的裁剪、逐帧重绘和贴回节点。代码仅在用户选择对应阶段时连接它们。

自定义二采需更新 FREYJA 节点包，加载 `FreyjaH3UpscaleEncode`、`FreyjaH3LoadLatent`、`FreyjaH3TileModel`、`FreyjaH3FinishSize`。原生时间窗需当前 ComfyUI；二采需要原生 `LatentUpscaleModelLoader`、`MiniMaxH3LatentUpscaleWithModel` 节点和已配置的兼容权重，权重放在 `models/latent_upscale_models/`。不再安装第三方 SpatialTiles；若以前安装过也无须由 FREYJA 自动卸载，当前图不会引用它。静态能力仅表示网关支持，不证明算力节点在线或依赖已安装。

本次不提交视频渲染任务，不探测或重启离线算力节点。验证覆盖阶段组装、引用契约、失败恢复、复用、任务快照、MCP、前端构建及四套主题模拟交互；真实画质、显存和接缝效果仍需节点恢复后评估。
