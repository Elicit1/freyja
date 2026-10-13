# MCP 外部 Agent 字段核对

核对依据：MCP 字段白名单与 JSON Schema、各业务 Service 的 `create/update`、工作台 `CharacterDrawer.vue` / `ShotDrawer.vue` / `ReferenceMediaSlots.vue`。这里的“必填”指保存业务记录的要求；保存草稿与准备好可用的提示词卡片是两个不同阶段。

## 创建业务记录

单条创建使用各对象的 `create_*` 工具，需要 `requestId,dramaId,data`；`create_project` 省略 `dramaId`。跨对象批量创建需要 `requestId,items`，每项需要 `clientKey,type,data`。操作已有项目需要顶层 `dramaId`，不要在 `data` 中重复填写。业务 ID 使用字符串，仅跨对象创建批次可以引用此前的 `clientKey`。

| 记录类型 | `data` 最小必填 | 可以省略的内容 |
|---|---|---|
| PROJECT | `title`、`aspectRatio` | 题材、风格、简介等；新建项目需独立权限，画幅须从启用的系统选项选择 |
| EPISODE | 无（可为 `{}`） | 集号、标题、时长由业务服务给默认值 |
| SCENE | `episodeId` | 场次序号、名称、环境属性、场景资产关联 |
| SHOT_GROUP | `sceneId` | 组号、名称、叙事用途 |
| SHOT | `sceneId` | 镜头组、序号、名称、时长、所有提示词和导演计划；默认首尾帧模式，默认时长 3 秒 |
| CHARACTER | `name / canonicalName / displayName` 至少一个非空 | 中文外貌、稳定身份 `appearancePrompt`、负向词、身份参考图、声音样本；不会自动创建造型 |
| CHARACTER_LOOK | `characterId`、`lookName`、`designDesc` | `outfitPrompt`（服装专属 Prompt）、`appearancePrompt`（该造型特有妆发/状态）、负向词、参考图；首个造型自动成为默认造型 |
| RESOURCE_SCENE | `name` | 中文描述、`scenePrompt`、负向词、参考图 |
| PROP | `name` | 类型、中文描述、`propPrompt`、图片 |
| KEYFRAME | `name`，且 `frameUrl / description / prompt` 至少一个非空 | `shotId`、用途类型、负向词；可以先创建文字草稿 |

`designDesc` 是造型的视觉概念描述，是现有业务校验的必填项；不能把它与可省略的英文妆发/状态 `appearancePrompt` 混同。MCP 不额外要求人物/造型先运行内部 AI 生成提示词。

项目 `genre` 是最多64字符的自由题材文字，`get_business_options(dictType=drama_genre)` 返回固定建议，不能视为穷尽枚举。已有建议可沿用其字典值；新题材直接填写自然语言名称，如“都市复仇悬疑”，无需编造未定义代码。题材由项目保存，剧集继承，不在 EPISODE 的 `data` 中提交。画幅、状态、景别等字段仍遵循各自约束。

创建项目必须显式提交 `aspectRatio`。先读 `get_business_options(dictType=drama_aspect_ratio)`：未指定时询问用户，让其选择已有比例；用户明确委托 AI 决定时优先选启用的 `16:9`，不可无提示地默认 `9:16`。服务端拒绝未填写、未收录或已停用的比例。追加剧集及修改其他字段时保留项目原画幅。

单条修改使用 `update_*`；批量修改按对象拆分，例如 `batch_update_shots / batch_update_characters`。请求需要 `requestId,dramaId`，单条或每个批量项需要 `recordId,expectedFingerprint,data`，不接收 `type`。创建必填字段不要求在补丁中重传；`data` 至少一个字段，省略字段保留原值。引用数组提交时替换整个数组，图和音频按提交顺序保存。

单条删除使用 `delete_*`，需要 `requestId,dramaId,recordId,expectedFingerprint`；跨对象批量删除使用 `batch_delete_records`，每项额外需要 `type`。不用填写业务 `data`。先解除引用并先子后父，仅逻辑删除且保留媒体文件。完整工具映射见 [MCP 使用说明](mcp-api.md#各业务对象独立-crud)。配置页“业务 CRUD”根据服务端映射展示 10 类对象的单条 CRUD 和独立批量修改状态。

## 填写提示词工作台

| 使用方式 | 外部 Agent 应填写的内容 | 无需提交 |
|---|---|---|
| 多模态参考模式 | `generationMode=REFERENCE_MODE`、最终 `prompt`、按 Prompt 图号排列的 `refImages`；有参考声音时填写按音频号排列的 `refAudios` | 首帧/尾帧提示词、导演计划、内部 AI 的中间产物 |
| 首尾帧模式 | `generationMode=FIRST_LAST_FRAME`、`videoPrompt`；需要图片提示词时填写 `firstFramePrompt / endFramePrompt`，有现成图片时填写 `previewImageUrl / endFrameImageUrl` | 参考模式的 `prompt`、多模态媒体数组、导演计划 |

负向提示词、场景补充提示词、人物动作/情绪/站位和道具提示词均按需填写。它们不是创建分镜的必填字段；已有图片可以直接关联，不要求为图片补造英文 Prompt。

`directorPlan / directorPlanMode` 是兼容手工编辑的可选高级内容。外部 Agent 的常规流程省略两者，直接保存卡片。服务端不会补生成导演计划；修改时省略也不会清除原计划。声音设计如 `overall_soundscape / non_diegetic_music` 由外部 Agent 写入最终提示词正文，无需填写内部提示词生成接口的 DTO、指纹或返回包装。

## 参考图、声音与弱绑定

参考图每项需要 `sourceType`、非空 `imageUrl`，不接受 `url/type/assetId` 别名。除 `UPLOAD` 外，需要真实的 `sourceId`：`SCENE` 对应场景资产、`PROP` 对应道具、`KEYFRAME` 对应关键图、`CHARACTER` 对应人物身份图、`CHARACTER_LOOK`（兼容 `CHARACTER_REFERENCE`）对应造型图。造型图还需要明确 `characterId`，`lookId` 可省略（服务端根据明确的来源造型 `sourceId` 补齐，并统一保存为 `CHARACTER_REFERENCE`）；人物身份图省略 `characterId` 时按明确的来源人物 `sourceId` 补齐。`UPLOAD` 不需要 `sourceId`，可以显式用 `characterId / lookId` 弱绑定已有主体。公开 Schema 保留可识别的对象字段，条件必填在业务入口校验，避免客户端显示 `unknown` 交叉类型。

`usageRole` 标识主体/画外人物/场景/道具/风格等用途，`referenceRole` 标识身份、造型、组合、姿态或风格约束，均由外部 Agent 按需要明确提交。图片中的人物不一定要出镜：可用 `NON_SUBJECT` 标明画外人物。`characterRefs` 是镜头的人物业务关联，参考图上的 `characterId / lookId` 是图片的弱绑定；服务端不通过文字猜测对应关系，不强迫每张图成为出镜人物。

参考音频每项需要 `sourceType`、非空 `audioUrl`；`TTS` 还需要 `characterId`，仅引用已有声音。`duration`、`usageMode`、台词 `text`、语言均按需填写；已知时长应真实填写，单段 2–15 秒，总长不超过 15 秒。音色、对白、配乐和音效用途应通过 `usageMode` 明确表达，不依靠服务端猜测。

媒体 `id` 是槽位标识，可省略，由 MCP 服务补全；显式提交时必须在同一数组内唯一。它不是雪花业务 ID。最多 9 张图片、3 段音频。`refImages[0]` 对应 `<Picture 1>`，`refAudios[0]` 对应 `<Audio 1>`；服务端不排序、不筛除空槽、不解析提示词重绑主体。调整顺序时，外部 Agent 应在同一补丁里更新最终提示词和媒体数组。

图片上传使用 MCP 工具 `upload_asset_image`，必填 `requestId,dramaId,purpose,dataBase64`，文件名 `fileName` 可省略。支持 PNG/JPEG/静态 WebP，单张最多 10 MiB；上传后把返回的 URL 填到人物/造型/场景 `referenceImageUrl`、道具 `coverUrl`、关键图 `frameUrl` 或分镜参考图的 `imageUrl`。上传与资产关联分为两个业务操作；资产可以在上传后创建，无需补填内部 AI 的中间字段，具体示例见 [MCP 使用说明](mcp-api.md#agent-图片上传)。

现有业务 REST `POST /res/asset/upload` 保持可用，它不属于 MCP，也不使用 MCP Bearer 鉴权。MCP 不调用内部生图功能，也不根据外部 URL 下载或归档文件。

## 不对外开放

内部 Agent/会话、推理模型与提供商、AI 提示词生成、导演 AI 规划、系统 Prompt、剧本拆解、生图/视频/TTS 执行、AI 任务与模型调度均不作为 MCP 工具。Skill 的已启用发布版本知识通过 `list_skills/read_skill/read_skill_file` 只读提供，不执行附属脚本。生成结果的提示词文本和媒体 URL 是业务内容，可以直接填写；不能据此调用内部 AI 生成接口。

## 完整卡片与批量素材交付

业务最小必填仅保证草稿可保存。用户要求完整分镜时，外部 Agent 应补齐基本规格、时长、剧本/动作、适用的对白/旁白/音效和人物造型、场景、道具、关键图关联。景别/运镜无明确选择可以填 `AUTO`，对应锁保持 `false`；已有锁须保留。没有依据的剧情、台词和视觉状态仍保持空，不由 Java 猜测。

人物/造型设定图先从 Skill 目录读取 `character-sheet-design`。用户要求一张图内的三视图、头部特写与全身分区、服饰细节和透明/白底时，外部 Agent 按规范生成并检查实际图像，再上传和关联 URL；不把排版指令混入人物稳定身份或服装专属 Prompt。MCP 的初始化说明、指南、目录工具和参考图字段提供这一指引，但不会强制第三方模型执行 Skill。

先读 `h3-prompt-writing` 及其参考文件：首尾帧视频用 `references/base-en.txt` 的三段式填 `videoPrompt`；参考模式用 `references/ref-en.txt` 的六段式填 `prompt`。完整图片卡片不能代替视频提示词；保存后应读回核对最终卡片及媒体编号。具体段名和流程见 [完整分镜与 H3 流程](mcp-api.md#外部-agent-的完整分镜与-h3-流程)。

批量图片用 `batch_upload_asset_images`，顶层 `requestId,dramaId,items`，每项 `clientKey,purpose,dataBase64,fileName?`。每批 1–20 张，单张最多 10 MiB、整批最多 20 MiB，返回有序结果和 `urlMap`；通过该映射填写资产和参考图字段。不要输出 Base64，素材包通过有工作区权限的普通文件编辑工具保存。

## 检查发现与已实施修正

1. 创建 Schema 原本把所有业务字段都标为可选，依赖业务 Service 才报必填错误。已按上表发布创建约束，同时保留更新补丁的可选字段。
2. 原本拒绝空 `data`，导致不需要额外字段的 EPISODE 也必须凑字段。创建现已允许业务默认值，更新仍禁止空补丁。
3. 媒体原本只要求 `sourceType`，可保存缺 URL 的卡片，后续内部素材清单会跳过空图，造成图号错位。现已拒绝空 URL、补全槽位标识、明确条件必填的资产 ID并保留顺序。
4. 指南和完整配置示例原本把导演计划放在常规流程中心，现已改为直接填写卡片的示例，明确计划可跳过、资产英文 Prompt 可省略。
5. 造型参考图省略冗余 `lookId` 后，下游素材清单可能改用默认造型；现已由明确的 `sourceId` 补齐对应造型 ID，不解析文字、不猜测视觉状态。
