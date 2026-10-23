# MiniMax H3 与 FREYJA 映射

## 两层职责

本 Skill 决定摄影和拆镜；`h3-prompt-writing` 及其当前发布的基础/参考规范决定最终段落、时间、对白和媒体标签。使用 `list_skills`、`read_skill` 取得当前版本，再以相同 `versionId` 调用 `read_skill_file`；官方格式和本页示例冲突时按当前规范修正。

下文摄影片段是原创示范，不是已验收成片，也不代表接口新增了轨迹或摄影机控制参数。默认不调用模型，不新增参考图、视频或音频。素材不足先报告，不用虚构编号补齐。

## 平台字段与 H3 段落

| 平台模式 | 写入字段 | 完整 H3 结构 |
| --- | --- | --- |
| `FIRST_LAST_FRAME` | `videoPrompt`，必要时配套静态 `firstFramePrompt` / `endFramePrompt` 或真实帧 URL | `integrated_multimodal_description` → `overall_soundscape` → `non_diegetic_music` |
| `REFERENCE_MODE` | `prompt` 与实际有序 `refImages`，可选显式音频 | `subject_definitions` → `summary` → `retention_analysis` → `detailed_description` → `overall_soundscape` → `non_diegetic_music` |

先确认实际工作流接受何种输入，不能仅靠平台枚举推断所有 H3 输入模式已开放。只有运镜或分镜方案需求时不强制生成完整提示词；请求最终卡片时必须保留对应完整结构。

- 基础模式的运镜、动作、对白写在 `integrated_multimodal_description`；参考模式写在 `detailed_description`。正文按当前 H3 规范用英文，原始台词保留原语言和标点。
- 每次请求从 `[Shot 1]` 开始，不加切镜时间戳。后续确有请求内切镜且工作流支持时，按 `[Shot N] At MM:SS.mmm, ...` 写严格递增且在时长内的切点；不是每个动作阶段都增加一个 `[Shot N]`。
- 通常一个 FREYJA 业务分镜对应一次连续 `[Shot 1]`。平台分镜编号和 H3 局部编号不能混同；不要把镜头组里第五个业务分镜写成只有 `[Shot 5]`。
- 默认不加背景音乐：`non_diegetic_music: N/A`。环境、方向与远近层次按 guide 声音规范，不能为“运镜气势”擅自加配乐或多余音效。

## 首尾帧与摄影路径

有真实首帧时从该画面出发；同时有首尾帧时，连续摄影路径要能到达实际尾帧。保留人物、道具、灯光和空间关系，不用反打、瞬移或场景变形硬凑两张不相容的图。双帧的对齐声明按当前 H3 规范和平台要求写，尾帧秒数对应目标时长。

单首帧、单尾帧或双帧是否能提交，由实际工具/工作流确认。不能因示例展示双帧就编造第二张图；也不能用文本声明冒充素材已上传。纯静态帧提示词只写那个瞬间，摄影机路径和声音时间线写在视频正文。

## 参考媒体：转换角色，重新核对编号

| 从 Seedance 方法迁移的概念 | 在 H3 / FREYJA 中处理 |
| --- | --- |
| `@Image`、`@Video` 等 Seedance 标签 | 不直接复制。按当前 H3 规范重新定义 `<Subject N>`、`<Picture N>`、`<Video N>`、`<Audio N>`，编号必须来自本次真实输入 |
| 参考图作为角色身份或场景来源 | 在相应 `<Subject N>` 定义里说明来源和约束；不把它自动说成固定首帧 |
| 参考图作为具体帧或构图参考 | 明确首帧、过程关键帧、尾帧或构图用途，按其实际角色使用 `<Picture N>` |
| 视频只提供摄影机节奏或路线 | 只有当前工具实际接收了视频时才定义 `<Video N>`，明确仅参考摄影结构；不自动继承人物、场景或音轨，不冒充视频编辑/续写 |
| 用图片或文字分析过某个参考视频 | 可提炼成自然语言摄影方案；未实际提交给模型的参考视频不能在最终卡片虚构为 `<Video 1>` |

FREYJA 的 `refImages[].usageRole` 必须显式选择系统合法用途，不能把上游自由文本发给保存接口。当前完整用途为 `SUBJECT`、`NON_SUBJECT`、`SCENE`、`PROP`、`STYLE`、`FIRST_FRAME`、`KEYFRAME`、`LAST_FRAME`、`EDITED_KEYFRAME`、`COMPOSITION_ANCHOR`、`STORYBOARD_REFERENCE`、`SHOT_PLANNING_REFERENCE`；具体来源对应哪些可选值以当前工具 Schema / 下拉为准，KEYFRAME 来源仅用七种关键图用途。人物身份/造型细分用 `referenceRole`；不得把它与 `usageRole` 混填。

`retention_analysis` 按当前 H3 关系标记描述实际保留范围；音频沿用 `fully_copy` / `partially_copy` / `reference` / `weak_reference`，不得因借鉴 Seedance 把音色参考变成旧台词复制。没有实际参考需求时保持 `refAudios=[]`。

## 示例 A：已验证双帧的连续横向跟拍

示例条件：同一已确认人物、服装、走廊和照明，Picture 1 是开始行走的中景，Picture 2 是同侧机位横向跟随四秒后人物停下的中景；该路径已能在四秒内自然完成，工作流实际接受双帧。这里只演示既定行走，不新增台词或事件。

```text
How the reference pictures align with the target video — Picture 1 (from Shot 1) aligns with the 0.00-second mark of the target video; Picture 2 (from Shot 1) aligns with the 4.00-second mark of the target video.

integrated_multimodal_description: [Shot 1] Live-action, a continuous eye-level medium shot begins from the supplied first frame. The camera tracks laterally beside the walking character at a steady pace, maintaining the same distance and the same side of the corridor as the background passes behind. The character continues the established walk and comes to a natural stop as the camera settles into the supplied final composition. Preserve the visible outfit, corridor geometry and established lighting throughout the movement.

overall_soundscape: Footsteps follow the visible steps and cease as the character stops. Low corridor room tone continues without an abrupt change in loudness.

non_diegetic_music: N/A
```

实际提交前替换为真实时长与画面，不能把示例的四秒和构图直接套到任意双帧。

## 示例 B：一张已有起始画面图的参考模式

示例条件：Picture 1 是创作者已接受并提交的同侧双人中景，杯子在递出者右手，两双手均可见；图中身份、环境与照明已确认，剧本只要求完成递杯，目标时长四秒。槽位用途已明确为 `FIRST_FRAME`，但不据此承诺参考模式是逐像素硬首帧输入。无音频参考，无新台词。

```text
subject_definitions:
<Subject 1> is the giver on the left in <Picture 1>, providing the visible appearance and clothing reference.
<Subject 2> is the receiver on the right in <Picture 1>, providing the visible appearance and clothing reference.
<Subject 3> is the cup held in the giver's right hand in <Picture 1>, providing its visible shape and material.
<Picture 1> is the starting-frame reference for [Shot 1], establishing the medium two-shot, hand positions, room and lighting.

summary:
[keyframe completion + reference generation] A four-second continuous shot develops from <Picture 1> as <Subject 1> hands <Subject 3> to <Subject 2>, keeping both pairs of hands and the contact visible.

retention_analysis:
<Subject 1> (appears in [Shot 1]): fully_preserved - preserve the referenced visible identity and clothing.
<Subject 2> (appears in [Shot 1]): fully_preserved - preserve the referenced visible identity and clothing.
<Subject 3> (appears in [Shot 1]): fully_preserved - preserve the cup's referenced appearance during the handoff.
<Picture 1> ([Shot 1] first frame): fully_preserved - preserve its starting composition, hand positions, room geometry and lighting as the action develops.

detailed_description:
Live-action, consistent with the supplied image. [Shot 1] The shot begins from <Picture 1>. The camera holds a static eye-level medium two-shot as <Subject 1> offers <Subject 3> from the established right-hand grip. <Subject 2> takes the cup securely before <Subject 1> releases it. Keep the contact point and both pairs of hands visible throughout the continuous handoff, ending with the cup securely held by <Subject 2>.

overall_soundscape: Low room tone continues beneath the handoff, with a faint contact sound when the hands touch the cup.

non_diegetic_music: N/A
```

图像引用顺序改变就同步更新整张卡片；没有 Picture 1 时不得使用该示例。需要对白则按实际说话人顺序使用稳定 `(Sx)`，原文放在 `<d>[Chinese]原台词</d>` 内；不能凭参考图主体顺序猜说话人。

## 保存前核对

摄影句是否在正确正文内，最终卡片是否与本镜业务模式一致，起止画面与动作可达，媒体标签是否真的已提交且含义一致，时长和切点是否对齐；不传未支持的摄影 JSON 键。写入后读回核对。不把示例条件、内部风险分析或导演决策表混进台词、静态图提示词或业务枚举字段。
