# 接续模式与 MCP 执行

当创作者需要同一人物与环境下的连续长动作、长舞蹈、一镜到底，或要在现有镜头内部逐段生成并续接时读取。本流程延续已确认的剧情；仅调整媒体与技术分段不触发故事重写。视频参考与 ControlNet 选择另读 [reference-video.md](reference-video.md)。

## 先选择执行方式

| 需求 | 选择 |
| --- | --- |
| 普通短镜头，单次采样可承载 | 接续可关闭；普通请求最多 15 秒，同时服从模型更小的上限 |
| 同一镜头的长动作必须连续，或希望降低单次生成帧数 | 查询接续能力后，在同一个业务分镜内启用 `draft.enabled=true`，按较短片段串行生成 |
| 剧情本身需要反打、视点变化或叙事切换 | 规划多个业务分镜及镜头组，不把接续当作剪辑工具 |

接续模式与 `generationMode` 是独立设置。`REFERENCE_MODE` 的各片段可挂普通图片、视频和音频；`FIRST_LAST_FRAME` 的各片段须显式设置工作流要求的首尾帧，不接受 Ref2VA 普通视频引用。开启接续后，即使总时长不超过 15 秒，也可能按所选段长产生多个片段；计划只需一段时无需制造接缝。

当前产品计划上限为 300 秒、最多 100 段，这些是参数边界，不是当前 GPU 可完成的长度或质量保证。先查询模型和在线节点，再用短片验证。5/8/10/15 秒是单次生成档位，不是必须拆成对应时长的业务镜头；新草稿当前默认 124 帧段长、22 帧重叠，已有草稿保持原配置，具体值从选项读取。

## 已发布工具与调用顺序

只使用当前已发布工具，所有业务 ID 保持十进制字符串。先确认授权项目与真实镜头，读取 `get_shot` 的当前内容；MCP 不替外部 AI 再调用内部提示词规划模型。

1. 调用 `get_shot_continuation(dramaId, shotId)` 取得完整 `draft` 和 `editFingerprint`。调用 `get_shot_continuation_options` 查询档位、提供商与匹配当前模式的视频模型；再带配套的 `providerId + modelCode` 查询实时能力、`controlTypes`、`outputSizes` 和 `aspectRatios`。不带模型时的类型枚举只表示协议选项，不证明节点已安装或能执行。
2. 在读取的完整草稿上调整 `enabled`、`durationSeconds`、`segmentFrames` 和 `overlapFrames`，调用 `plan_shot_continuation(auto=true)`。它只做帧规划，不调用 AI/GPU、不保存。需要保留手工范围时用 `auto=false` 校验；自动重分段若影响已有内容、锁定或绑定会拒绝，不能清空草稿绕过保护。关闭接续可保留草稿；读回普通镜头的实际时长，不能据长草稿向普通渲染提交超过 15 秒的请求。
3. 外部 AI 按已确认剧本编写返回的 `draft.segments[]`，逐段指定人物/造型、场景或关键图、道具、提示词及真实素材。需要长视频参考时，调用 `split_shot_reference_video`，传当前完整草稿和明确的 `referenceVideo`；`targetSegmentIds` 省略为全部片段，显式选择须是当前有效且不重复的片段 ID。它探测源视频并返回范围绑定，不实际生成裁片、不保存。
4. 调用 `save_shot_continuation` 保存完整草稿，带稳定 `requestId` 和最新 `expectedFingerprint`。这是完整草稿保存，不是只传变化字段的补丁。读回核对保存内容并取得新指纹。
5. 调用 `preview_shot_continuation`，传最终草稿、同一提供商与模型。仅 `renderable=true` 才可继续；逐项处理 `issues`。后续使用这里的 **`planHash`**，不能拿纯帧规划返回的哈希代替模型预览哈希。
6. 调用 `prepare_shot_continuation`，传当前指纹、稳定请求 ID、提供商、模型、选项中有效的 `size` 和预览的 `planHash`。轮询 `get_shot_continuation_preparation` 至 `READY`；失败时处理具体原因，不能直接提交。
7. 创作者已授权生成时，调用 `render_shot_continuation`，保持相同计划、模型与尺寸，使用最新指纹和独立稳定请求 ID。通过 `get_shot_continuation_render` 查询真实任务。任务 ID 仅表示提交；成功状态及实际输出地址才表示生成完成。

同一写请求超时后用原 `requestId` 和完全相同的参数重试；输入变化使用新请求 ID。指纹冲突时重新读取并合并，保留创作者修改。编辑提示词、素材、时间范围或模型后，重新保存、预览和准备，不复用旧哈希或旧准备状态。工具未发布、写权限关闭或项目无权限时，报告对应限制，不绕到私有接口或原始工具注册表。

## 片段内容与素材归属

- 每段用 `name`、`scriptContent`、`prompt` 及按需的 `firstFramePrompt/endFramePrompt/negativePrompt` 表达本段内容；接续段的视频正文保存在 `segment.prompt`，不要误写成父分镜的 `prompt/videoPrompt`。H3 格式仍读取 `h3-prompt-writing` 的对应模式规范。
- 人物与造型填本段 `characterRefs`，道具填 `propRefs`，场景与关键帧背景互斥。图片为 `referenceImages`，视频为 `referenceVideos`，独立音频为 `referenceAudios`。父分镜或 `imageSources/videoSources` 托盘有素材，不代表片段已挂载；同一素材用于多段也要各段显式绑定。
- 图片项遵守当前 Schema，包含真实 URL、`id/sourceType/usageRole` 等必填字段；角色、造型与资产从授权项目或公共库选择，不编造 ID。首尾帧模式使用本段 `firstFrameImageUrl/lastFrameImageUrl`，不把普通参考图冒充锚点。末段尾帧落在最终裁掉的网格补齐区时，按预览提示处理，不能声称尾帧已保留。
- 图片、视频编号在每段内重新按挂载顺序计算，分别引用 `<Picture N>/<Video N>`；启用的视频原音先占用该段 Audio 编号，再接独立参考音频。没有真实绑定时不写引用标签。
- 每段剧本与提示词只安排本时间范围内的动作和对白。共享范围承接上一段已有状态，不把全镜动作、整段台词逐段重播，也不额外写入新的剪辑切镜。源视频名和描述不能替代实际观察或创作者说明。
- `segment.locked` 对应“锁定自动规划”：工作台的 AI 规划跳过该段，仍可用其作为上下文；它不阻止手动编辑或渲染，也不是上游视频缓存锁。外部 AI 同样保留已锁定内容；只有用户明确要求修改该段时才改。自动重分段会保护锁定内容，手动移除/重建仍会清除它，不擅自执行。

## 四种接续方式

新草稿可用 `schemaVersion=2`。`draft.continuity` 是全局设置，片段
`continuityOverride=null` 表示完整继承；有覆盖时提供完整模式设置，不逐字段叠加。
缺少新字段的历史配置按 `GUIDE` 读取，不能擅自升级历史不可变任务。

| `mode` | 用途与参数 |
| --- | --- |
| `GUIDE` | 干净尾部通过 AddGuide 引导；兼容现有行为 |
| `GUIDE_CHROMA` | 临时上下文画面加色度噪声，尾部渐弱；`chroma={strength:0.45,seamStrength:0.10,taperFrames:3}` 为 FREYJA 配方初始值 |
| `LATENT_REDRAW` | 继承原始 AV latent 并受控重绘；`redraw={strength:0.10}`，不等同整段 denoise |
| `LATENT_PROTECTED` | latent 上下文噪声与干净帧锚定；`protection={noiseStrength:1,useFirstFrameAnchor:true,useTailAnchor:false,tailAnchorStartFraction:0.75}` |

先通过带提供商和模型的选项查询读取 `capabilities.h3.continuationModes`。
新模式需要 FREYJA H3 节点包；`available=false` 按具体缺失节点处理，不通过
修改人工验收标记绕过。普通编辑器能保存策略不等于当前节点已支持渲染。

首段没有前驱，但链中使用 latent 模式时系统会保存各段主采样器的一采 AV
latent 到 MinIO。后续段及锚点只能使用兼容画布、底模、VAE、LoRA和调度配置的
原始产物；缺少历史 latent 时可重生成前驱或显式改用画面引导，不把 MP4 重编码
说成原始 latent，也不静默降级。`anchorSourceSegmentId` 只能指向前序段，省略为首段。

两个 latent 模式目前关闭片段内 `contextWindows`。默认首帧保护可与 ControlNet
联用；可选尾部锚定暂不与 ControlNet 联用。彩噪、重绘、上下文噪声是不同配方，
不要互换强度值或叠加多个前缀执行器；视觉噪声不作用于动作参考、控制特征或音频。

`ranges[].effectiveContinuity/contextRequirement/saveLatent/anchorSourceSegmentId`
给出实际策略与产物依赖。模式、参数或锚点来源变化后重新保存、预览、准备。
`ARCHIVING` 表示已有视频正在归档交接产物，失败优先恢复归档，不提交新生成任务。
锁定自动规划同样保护用户选择的模式与参数。社区研究和离线检查不能作为实际画质保证。

## 时间轴、源范围与 24fps

服务端用 24fps 和 H3 帧网格计算半开区间 `[startFrame,endFrame)`；优先使用规划返回值，手工调整后再次校验。成片请求帧数、实际生成帧数和各段保留范围分别读取 `outputFrames/renderFrames/ranges`。例如 5 秒请求可能生成 124 帧，最终按目标裁剪，不能把 124 帧当作恰好 5 秒。

参考视频有自己的源时间范围。`split_shot_reference_video` 默认按速度 1:1 把同一源范围映射到全片时间轴，保留相邻片段的真实重叠；自动绑定返回的 `sourceStartFrame/sourceEndFrame` 与秒数应成对保留。素材准备阶段先按统一 24fps 时间轴重采样，再按绝对帧范围裁片，不改变动作速度。源时长不足时修正范围或计划，不能循环、拉伸、冻结末帧或伪造源帧掩盖不足。新增片段或重新规划后要重新分配源范围。

`overlapFrames` 是计划参数，尾段为满足最短生成长度可能扩大实际重叠；使用返回的 `ranges[].overlapFrames` 和保留范围，不假定每处接缝都相同。ControlNet 控制源必须覆盖本段真实输出范围，且满足参考视频的独立时长、帧数与像素预算；生成段的上限不自动扩大参考预算。

## 接续、窗口与特征提取各自负责什么

| 设置 | 用途 | 使用条件 |
| --- | --- | --- |
| 接续 `enabled` + 段间重叠 | 根据所选策略交接上一段实际画面或原始 AV latent | 同一镜头的跨段连续；系统自动处理，不占普通 `<Video N>` 编号，无需用户再挂一份尾部视频 |
| `contextWindows` | 片段内部的采样窗口 | 是独立可选能力；普通串行接续可关闭，不能当成开启接续的必需项 |
| 片段 `controlNetEnabled` | 从本段明确选择的参考源提取控制信号并应用 | 根据 [视频参考与特征提取](reference-video.md) 选择；无视频也能接续，有视频也可不提取 |

窗口与 ControlNet 同时启用时，必须确认实时 `contextWindowsWithControlNet=true`。人工验收状态字段已移除，旧 UNVERIFIED/VERIFIED 值不再影响调用；按实际在线状态、缺失节点、工作流版本及素材预算处理预览问题，不虚构能力或生成结果。

## 输出与恢复

串行执行按重叠裁切并合成完整视频，最终保存一条完整 Take，不把每段都当作镜头当前版本。`reuseFromTaskId` 仅在同一镜头且输入未变时复用连续前缀；上游结果、提示词、种子、范围、模型或素材变化会使相应段及后续段不再复用，不用 `locked` 代替缓存判断。

遇到状态不明或提交超时，先查真实任务与已有执行，不另起请求盲目重复生成。生成后按正常速度观看各接缝前后，检查动作重播、人物/服装漂移、背景跳变、重复对白与音画同步，并核对实际帧数和时长。当前固定重叠裁切不代表自动找到最佳切点或修复所有接缝，完整解码通过也不能证明无缝。
