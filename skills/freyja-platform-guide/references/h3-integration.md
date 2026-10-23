# MiniMax H3 与 FREYJA 的接入约定

使用 H3 编写提示词、选择生成模式或配置音频参考时读取。模型语法使用系统已发布的 `h3-prompt-writing`：基础模式读 `references/base-en.txt`，全参考模式读 `references/ref-en.txt`；不要把 FREYJA 的枚举、资产规范或当前算力限制当作所有 H3 接口的要求。

设计机位、推拉摇移、运动节奏和拆镜切点时，先通过 Skill 工具读取 `minimax-h3-camera` 及其 `references/camera-language.md`、`references/shot-planning.md`；编译 H3 卡片时再读其 `references/h3-mapping.md`。摄影方法与最终协议分工明确，不使用 Seedance 的 `@` 媒体标签或专有参数；`h3-prompt-writing` 的当前发布规范决定最终格式。

## 平台模式与模型能力

| 平台模式 | 当前输入与用途 | 提示词字段与结构 |
| --- | --- | --- |
| `FIRST_LAST_FRAME` | 首帧/尾帧约束；实际支持单首帧、双帧还是其他组合，以所选模型、工具及工作流为准 | `videoPrompt`；基础三段式 |
| `REFERENCE_MODE` | 按有序素材定义人物、场景、道具与音频参考关系 | `prompt`；参考六段式。当前服务会同步 `videoPrompt` 别名，不能分别填写矛盾内容 |

- H3 的 T2VA、I2VA、FL2VA、L2VA、Ref2VA 是模型输入模式，不能仅凭平台 `generationMode` 枚举推断当前工作流开放了所有组合。
- H3 基础图生视频模式可以生成原生对白。闭嘴首尾图不意味着整个镜头不能说话；首尾状态、中间表演和台词须在时长内相容，并验收口型。
- 需要跨镜头角色音色参考、多个角色/场景/道具引用时，优先 `REFERENCE_MODE`。需要用实际首帧接前镜或明确落幅时，优先关键帧模式。逐镜选择，不为追求混用而切换模式；同一模式可以连续用于符合条件的镜头。
- `REFERENCE_MODE` 中标记 `FIRST_FRAME` 的参考图不等于关键帧输入。严格起幅优先使用工作流的真实首帧输入；两种模式均须验收，不能承诺逐像素吻合或永不变脸。
- 基础模式的原生对白能力，不代表该工作流支持外部参考音频。需要声线参考或复制音轨时，先核实其音频输入通道；不支持则使用已开放的参考模式，不虚构参数或保证音色一致。

## 官方结构与平台校验

基础模式按顺序保留三个非空段落：

```text
integrated_multimodal_description: ...
overall_soundscape: ...
non_diegetic_music: N/A
```

参考模式按顺序保留六段：`subject_definitions`、`summary`、`retention_analysis`、`detailed_description`、`overall_soundscape`、`non_diegetic_music`。编号必须来自最终提交的素材顺序，不能编造 `<Picture N>`、`<Audio N>` 或 `<Subject N>`。

当前 FREYJA 的首尾帧提示词校验要求在三段正文之前提供 Picture 1/Picture 2 的秒数对齐声明，尾帧秒数与当前镜头 `duration` 一致，例如目标时长为 6 秒时：

```text
How the reference pictures align with the target video — Picture 1 (from Shot 1) aligns with the 0.00-second mark of the target video; Picture 2 (from Shot 1) aligns with the 6.00-second mark of the target video.
```

这是当前 FL2VA 输出契约，不能据此伪造第二张图片、假称单首帧已有尾帧，或把双帧声明无条件套到 I2VA。使用单首帧前核实工具与工作流能否接受；若服务仍要求双帧声明而输入只有一帧，如实报告接入限制，选择平台实际支持的方案，不用虚构素材绕过校验。

双帧模式正文聚焦连接首尾的动作与相机运动，避免反复复述两幅静态画面；必要的端点对齐声明仍须保留。

## 时间标注与帧率

- H3 官方格式允许且使用秒数：关键帧用 `0.00-second mark` 等对齐；第一镜为 `[Shot 1]`，不添加切镜时间戳；后续切镜用 `[Shot N] At MM:SS.mmm, ...`，时间须递增并落在目标时长内。
- 不为凑格式在一个平台分镜内增加切镜。单个连续镜头一般只用 `[Shot 1]`，动作顺序可用自然语言表达，需要时间点时按所选官方规范写。
- 不得以帧数替换官方秒数对齐声明。帧数适用于工作流参数、资源估算及成品检查，不能据此保证提示词可精确控制某一帧。
- 当前 H3 生成链路通常以 24 fps 换算；这是当前链路配置，不是所有 TTS/视频模型或后处理的统一要求。补帧后的输出帧率、换模型后的帧率均须读取实际配置或媒体元数据。
- 请求时长、网关对齐后的有效帧数及成品实际时长须分别核对。当前部署 10 秒及以上容易 OOM，遵循 [主指南第 15 节](../SKILL.md#15-当前部署的视频时长oom-风险与合理拆镜deployment-resource-limits--shot-planning)，不要用模型标称时长覆盖算力限制。

## 对白、画外音与嘴形

设计声音的听点、方位、远近、遮挡、运动与跨镜连续性时，先读 [声音设计与空间连续性](sound-design.md)。这些是提示词与验收要求，不代表 H3 已提供精确声像或自动跨镜混音参数。

- 将人物身份、稳定的说话人 ID、动作与语气放在 `<d>` 外；`<d>` 内只放语言标签与原始台词，保留原文和标点。参考模式可写 `<Subject 1> (S1)`；基础模式无需虚构参考主体编号。
- 画外音、旁白和内心独白可以叠加人物反应镜头。说明实际发声者、画外属性及哪些画内人物未说话，避免将旁白绑定为画内对白。
- 基础模式按官方写法使用 `says in an off-screen voiceover`，并在每个画外音 `<d>` 块之后说明对应出镜人物嘴唇保持闭合，例如：

```text
The woman (S1) says in an off-screen voiceover: <d>[Chinese] 我还记得那条路。</d> while her lips remain completely closed.
```

- 参考模式保留对应 `<Subject N> (Sx)` 或稳定声音描述，明确 `off-screen`；人物在画内但只有内心独白时，同样明确嘴唇保持闭合。旁白与画内角色不同时，不把旁白音色/说话人 ID 绑定给听者。
- 静态生图提示词只写画内可见状态，省略画外人物与声音来源；视频多模态正文必须包含实际画外声音及台词。对白在多模态正文中表达，不一概塞入 `overall_soundscape`。
- 生成后检查发声归属和嘴形。空镜/道具特写可作为降低误对嘴风险的备选，不是所有画外音的强制镜头类型。提示词是引导，不能保证绝不误对嘴。

## 参考音频的用途与关系

| 用途 | 处理方式 |
| --- | --- |
| 角色音色与表演 | 按 [配音中心流程](dubbing-center.md) 先查同角色、情绪及特效适用的 READY 样本；默认 `reference`，不带入样本旧台词，不逐镜生成 |
| 环境声或音效质感 | 可以用于无对白镜头；明确参考范围，在 `overall_soundscape` 中说明，不挂载无关角色试音 |
| 仅观众可听的配乐 | 仅在创作者启用 BGM 且当前工具接受素材时使用，在 `non_diegetic_music` 中说明；禁用 BGM 时该段严格为 `N/A`，不附加配乐参考 |
| 没有明确参考需求 | 保持 `refAudios=[]`；表示没有外部参考，不等于静音，不会自动关闭原生对白或环境声 |

- 所有用途仍遵守当前工具、素材来源和数量/时长限制；文档允许的音频用途，不代表配音中心自动具备任意音效/音乐上传能力。只选择系统已有或当前工具可合法接收的实际素材。
- `reference` 不直接复制音频信号。角色声音按用户意图仅参考音色、情绪和特效，当前台词来自剧本；`weak_reference` 仅用于明确需要弱参考时。
- 只有用户明确要求 `fully_copy` / `partially_copy` 才选择复制关系。当前角色对白按配音中心流程重新生成音轨并检查时间窗；音效/音乐素材按用户指定范围使用，不调用 TTS 重生非人声素材。
- `fully_copy` 是把完整源音频作为完整最终音轨；若只复制某个声音层、替换对白或另外添加环境声，则应明确为 `partially_copy`，并与用户要求一致，不能悄悄变更关系。
- 当前参考音频单段 2–15 秒、最多 3 段、总长不超过 15 秒。普通音色样本建议 5–10 秒，这是制作建议，不是所有 TTS 的统一最佳区间，也不要求与视频等长。
- 生成后检查实际声轨、意外语音、音色、情绪和复制片段的完整性；发现异常按实际问题修正，不能虚构“注意力必然撕裂”“100% 消除杂音”等机理或保证。

## 依据与维护

视频作为参考条件时，另读 [视频参考流程](reference-video.md)：`refVideos` 使用明确来源、秒级范围、原音开关与参考说明，按 `<Video 1>` 对齐；实际生成需要片段预检与模型在线验收，不能只上传视频就声称它已参与生成。

- [MiniMax H3 官方项目与提示词技能](https://github.com/MiniMax-AI/MiniMax-H3)
- [官方基础模式规范](https://github.com/MiniMax-AI/MiniMax-H3/blob/main/skills/h3-prompt-writing/references/base-en.txt)
- [官方全参考模式规范](https://github.com/MiniMax-AI/MiniMax-H3/blob/main/skills/h3-prompt-writing/references/ref-en.txt)
- 当前仓库接入：`ShotAiVisualPlanServiceImpl` 的 H3 输出契约与校验、`comfy_gateway/schemas.py` 的帧数换算、`comfy_gateway/workflow_engine.py` 的模式分发。工作流或接口升级后重新核实本文件的平台约定。
