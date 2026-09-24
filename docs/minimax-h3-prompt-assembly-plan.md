# MiniMax H3 专用分镜 Prompt 与多模态参考组装方案

## 1. 目标

将“分镜精细配置 → AI 智能生成提示词”从通用视频 Prompt 改造为符合 MiniMax H3 官方语义的专用 Prompt 流水线，重点满足：

1. `FIRST_LAST_FRAME` 使用 MiniMax H3 FL2VA 官方三段式格式；
2. `REFERENCE_MODE` 使用 MiniMax H3 Ref2VA 官方六段式格式；
3. 参考模式下仍必须写入场景描述、人物外貌、服装、道具及空间关系；
4. 有参考图时，使用 `<Picture N>`、`<Subject N>` 精确声明图片与人物/场景/道具的关系；
5. 有参考音频时，使用 `<Audio N>`、`(S1)` 和 `<d>[Language] ...</d>` 明确声音用途、说话人与台词；
6. Prompt 中引用的素材编号必须与最终进入 ComfyUI 节点的物理输入顺序完全一致；
7. Java 只负责结构化数据、映射、校验和持久化，不写自然语言关键字猜测逻辑。

本方案依据：

- MiniMax H3 官方仓库：<https://github.com/MiniMax-AI/MiniMax-H3>
- 官方 H3 Prompt Skill：<https://github.com/MiniMax-AI/MiniMax-H3/tree/main/skills/h3-prompt-writing>
- 官方 Base/FL2VA 指南：<https://github.com/MiniMax-AI/MiniMax-H3/blob/main/skills/h3-prompt-writing/references/base-en.txt>
- 官方 Ref2VA 指南：<https://github.com/MiniMax-AI/MiniMax-H3/blob/main/skills/h3-prompt-writing/references/ref-en.txt>
- ComfyUI H3 Ref2VA 节点说明：<https://github.com/Comfy-Org/embedded-docs/blob/main/comfyui_embedded_docs/docs/MiniMaxH3ReferenceToVideo/en.md>

---

## 2. 官方格式结论

### 2.1 FL2VA

MiniMax H3 FL2VA 的最终视频 Prompt 应由两部分组成：

1. 首尾帧和目标视频时间点的对齐声明；
2. 三个固定字段：

```text
integrated_multimodal_description: ...
overall_soundscape: ...
non_diegetic_music: ...
```

首尾帧模式通常应保持单镜头，描述首帧状态、可观察的中间动作变化，以及最终收敛到尾帧的过程。

### 2.2 Ref2VA

MiniMax H3 Ref2VA 的最终 Prompt 必须按以下顺序输出六个固定段落：

```text
subject_definitions:
summary:
retention_analysis:
detailed_description:
overall_soundscape:
non_diegetic_music:
```

参考素材使用：

```text
<Subject 1>
<Picture 1>
<Video 1>
<Audio 1>
```

编号按各自媒体类型独立从 1 开始，并在整份 Prompt 中保持含义不变。

### 2.3 对话与声音

实际发声主体使用稳定的 `(S1)`、`(S2)` 编号。对白必须使用：

```text
<d>[Chinese] 原始台词</d>
```

对白内容保持原文，不翻译、不改写。Prompt 其他说明使用英文。

参考音频必须明确用途，至少区分：

- 复用完整音频；
- 复用部分声音层；
- 仅参考音色和演绎方式；
- 仅参考音乐、节拍或音效质感。

不能只写“参考这段音频”，否则模型无法确定是复制音频内容，还是仅参考音色。

---

## 3. 当前实现与 H3 要求之间的差距

### 3.1 AI 生成 Prompt 时没有收到参考素材清单

当前 `ShotPromptDeriveDTO` 没有：

```text
refImages
refAudios
```

`ShotPromptDeriveModal.vue` 生成请求也没有发送这两个字段。因此文本模型不知道：

- 有几张参考图；
- 每张图对应哪个人物、场景或道具；
- `<Picture N>` 应该绑定什么；
- 有几段参考音频；
- 每段音频对应哪个说话人以及用于复制还是参考。

### 3.2 网关追加的是自定义通用格式，不是 H3 官方格式

当前 `comfy_gateway/reference_prompt.py` 追加：

```text
【参考素材关系】
【当前镜头】
【连续性】
```

并使用“提供的人物参考图对应角色”之类的通用说明，没有使用 MiniMax H3 官方的六段结构和 `<Picture N>` / `<Audio N>` 标签。

这会产生两个问题：

1. Java AI 生成一份通用 Prompt；
2. Gateway 又追加另一套关系描述；

最终 Prompt 存在重复、格式冲突和引用关系不明确的问题。

### 3.3 参考音频实际只连接了一段

虽然业务层 `refAudiosJson` 可以保存多段音频，但当前 Gateway：

- `main.py` 只上传 `request.audio_url`；
- `workflow_engine.py` 只连接 `ref_audios.ref_audio_0`；
- 其余 `REFERENCE_AUDIO` 只存在于 JSON 关系中，没有真正进入 ComfyUI 节点。

因此当前所谓“多参考音频”实际上只有第一段参与推理。

### 3.4 前端音频限制与 H3 官方限制不一致

当前 DTO 注释和页面允许 4 段参考音频，并允许总长度达到 20–30 秒。

MiniMax H3 Ref2VA 官方限制为：

- 参考图最多 9 张；
- 参考视频最多 3 段；
- 参考音频最多 3 段；
- 单段音频或视频 2–15 秒；
- 视频总时长不超过 15 秒；
- 音频总时长不超过 15 秒；
- 混合参考素材最多 12 个；
- 参考音频不能作为唯一参考输入，至少需要一张参考图或一段参考视频。

### 3.5 H3 工作流没有使用当前 negativePrompt

`machine_default.yaml` 中两个 MiniMax H3 模型都没有 `negative_prompt` 节点映射。因此当前保存的 `negativePrompt` 不会真正进入 H3 conditioning。

H3 必须遵守的身份稳定、禁止交换人物、禁止道具复制、避免突然切镜等约束，应写进官方结构中的 `retention_analysis` 和 `detailed_description`，不能依赖单独的负向词。

---

## 4. 推荐总体架构

```text
分镜表单与资产库
      │
      ├─ 镜头剧本、动作、对白、音效、时长
      ├─ 人物/服装/场景/道具文字描述
      └─ 有序参考图与参考音频清单
      │
      ▼
Java ReferenceManifest 构建器
      │  固定 Picture/Audio 序号与实体关系
      ▼
文本模型生成 H3 专用结构化 Prompt
      │
      ├─ FL2VA：对齐声明 + 三段式
      └─ Ref2VA：六段式
      │
      ▼
Java 字段与标签校验
      │
      ▼
数据库 video_prompt
      │
      ▼
Gateway 按同一 Manifest 顺序上传媒体
      │  不再追加旧版中文关系 Prompt
      ▼
MiniMax H3 ComfyUI 节点
```

关键原则：Prompt 编号和媒体物理顺序只能有一个真源。推荐由 Java 构建并持久化/透传 `ReferenceManifest`，Gateway 严格按 Manifest 顺序上传。

---

## 5. 建立 ReferenceManifest

### 5.1 图片清单

按 `shot.refImages` 当前持久化顺序分配：

```text
Picture 1 = refImages[0]
Picture 2 = refImages[1]
...
```

每项至少包含：

```json
{
  "pictureIndex": 1,
  "referenceId": "ref_xxx",
  "sourceType": "CHARACTER",
  "sourceId": "2032095628944588801",
  "entityName": "林默",
  "usageRole": "IDENTITY",
  "description": "...",
  "imageUrl": "..."
}
```

`usageRole` 建议支持：

```text
IDENTITY
SCENE_LAYOUT
PROP_APPEARANCE
STYLE
MOTION_KEYFRAME
```

角色参考图的 `description` 由资产数据提供：

- `ResCharacter.appearanceDesc`；
- 当前造型 `ResCharacterOutfit.outfitPrompt`；
- 角色名称和定位；
- 当前镜头中的站位、动作和情绪。

场景参考图的 `description` 由以下数据提供：

- 场景名称；
- `scenePrompt`；
- `timeOfDay`；
- `weatherAtmosphere`；
- `customScenePrompt`，存在时作为当前分镜覆盖描述。

道具参考图的 `description` 由道具名称、类型和 `propPrompt` 提供。

### 5.2 音频清单

按 `shot.refAudios` 当前持久化顺序分配：

```text
Audio 1 = refAudios[0]
Audio 2 = refAudios[1]
Audio 3 = refAudios[2]
```

为 `ShotRefAudioDTO` 增加：

```java
private String usageMode;
private String language;
```

`usageMode` 必须由创作者或音频生成流程显式指定，禁止 Java 根据文件名或台词关键字猜测：

```text
DIALOGUE_REUSE       直接复用该段对白声音
VOICE_TIMBRE         只参考音色、语速和演绎方式
BGM_REUSE            直接复用背景音乐
BGM_STYLE            只参考音乐风格、节奏或配器
SFX_REUSE            直接复用音效
SFX_REFERENCE        只参考音效质感
RHYTHM_REFERENCE     只参考节拍和时间节奏
```

TTS 生成流程在生成“本镜最终对白音轨”时可以显式写入 `DIALOGUE_REUSE`；角色母音/声音样本必须写入 `VOICE_TIMBRE`。

### 5.3 编号稳定性

以下各环节必须使用同一顺序：

1. 前端参考素材列表；
2. Java `ReferenceManifest`；
3. 文本模型输入；
4. `references` 请求数组；
5. Gateway 上传顺序；
6. ComfyUI `ref_image_0..N` / `ref_audio_0..N` 插槽；
7. Prompt 的 `<Picture 1..N>` / `<Audio 1..N>`。

ComfyUI 插槽内部从 0 开始，但 Prompt 标签从 1 开始：

```text
ref_image_0 -> <Picture 1>
ref_image_1 -> <Picture 2>
ref_audio_0 -> <Audio 1>
ref_audio_1 -> <Audio 2>
```

不得使用 HashMap 遍历顺序或数据库无序查询来生成编号。

---

## 6. 参考模式必须如何写场景和人物描述

“使用参考图”不等于“省略文字描述”。参考图提供视觉证据，文字 Prompt 用来明确哪部分需要保留、哪个实体对应哪张图，以及实体在当前镜头中如何出现。

### 6.1 有人物参考图

在 `subject_definitions` 中写一次完整身份定义：

```text
<Subject 2> is the character Lin Mo in <Picture 2>, a lean young Chinese man with short black hair, a narrow face and calm dark eyes, wearing the charcoal-gray tailored suit specified for this shot.
```

在 `detailed_description` 第一次出场时再次写“当前可见状态”，但不要反复复制整段外貌：

```text
[Shot 1] <Subject 2> stands in the left foreground of <Subject 1>, preserving the face, hairstyle and charcoal-gray suit from <Picture 2>. He lowers his gaze toward the brass clock...
```

### 6.2 有场景参考图

把场景本身定义为可保持的 Subject：

```text
<Subject 1> is the top-floor office environment in <Picture 1>, featuring dark walnut wall panels, floor-to-ceiling rain-streaked windows, a black stone desk and cold blue city light entering from camera right.
```

`retention_analysis` 明确保持空间结构和主要固定陈设：

```text
<Subject 1> (appears in [Shot 1]): fully_preserved - the office layout, window position, walnut panels, black stone desk and blue side-light direction remain consistent.
```

### 6.3 没有对应参考图但有文字资产

仍然把人物和场景描述写进 `detailed_description`，但不得伪造 `<Picture N>` 标签。

例如，只有角色参考图、没有场景参考图时：

- 角色使用 `<Subject 1>` / `<Picture 1>`；
- 场景直接写为英文环境描述；
- 不得写“the scene in `<Picture 2>`”。

### 6.4 多人物防串脸

每个人物建立独立 Subject，并在 `retention_analysis` 明确：

- 对应哪张或哪几张图片；
- 外貌、发型、服装和体型需要保留；
- 角色之间不得交换身份、服装或位置关系。

禁止把多个人物合并成一个 Subject。

---

## 7. Ref2VA 最终 Prompt 模板

文本模型在 `REFERENCE_MODE` 下应直接生成以下格式。模板中的标签必须来自 ReferenceManifest。

```text
subject_definitions:
<Subject 1> is the [scene name] environment in <Picture 1>, featuring [complete scene description, layout, fixed furnishings, time of day, weather and lighting].
<Subject 2> is [character name] in <Picture 2>, with [appearance description], wearing [current outfit description].
<Subject 3> is [prop name] in <Picture 3>, featuring [prop material and shape].
<Audio 1> is the voice-timbre reference for <Subject 2> (S1), providing [voice timbre, pace and delivery only].

summary:
[reference generation + audio reference] The target video is a single continuous [duration]-second shot showing <Subject 2> performing [core action] inside <Subject 1>. <Picture 1>, <Picture 2> and <Picture 3> preserve the scene, character and prop identities. <Audio 1> guides <Subject 2>'s voice timbre without copying its original words.

retention_analysis:
<Subject 1> (appears in [Shot 1]): fully_preserved - [scene elements and spatial layout to retain].
<Subject 2> (appears in [Shot 1]): fully_preserved - [face, hair, body proportions and current outfit to retain].
<Subject 3> (appears in [Shot 1]): fully_preserved - [prop appearance, count and ownership to retain].
<Audio 1>: reference - the target dialogue follows <Audio 1>'s voice timbre, pace and delivery without copying the source wording.

detailed_description:
The target video is in [stylePreset/styleTone translated into concrete visual language].
[Shot 1] A [shot size and angle] frames <Subject 2> at [position] inside <Subject 1>. [Concise visible character and scene anchors]. The camera [natural camera movement with amplitude and speed]. <Subject 2> [chronological physical action and expression change] while interacting with <Subject 3>. <Subject 2> (S1) says, <d>[Chinese] [exact dialogue]</d>. [Lip movement, action landing, final visible state and timing].

overall_soundscape:
[Room tone, ambience, footsteps, cloth movement, prop sounds and other diegetic non-dialogue sounds. State any Audio reference/reuse relationship that belongs here.]

non_diegetic_music:
[Concrete instrumentation, tempo, rhythm and volume evolution, or N/A.]
```

### 7.1 直接复用 TTS 对白音频

如果 `<Audio 1>` 就是当前镜头最终台词音轨，使用：

```text
<Audio 1> is the final synchronized dialogue track for <Subject 2> (S1) and is directly reused in the target video.
```

`retention_analysis`：

```text
<Audio 1>: fully_copy - <Audio 1> is reused as <Subject 2>'s complete synchronized dialogue track.
```

`detailed_description` 中仍应保留准确台词和唇形同步要求：

```text
<Subject 2> (S1) physically speaks in exact synchronization with <Audio 1>: <d>[Chinese] 原始台词</d>.
```

### 7.2 仅参考角色音色

使用：

```text
<Audio 1> is the voice-timbre reference for <Subject 2> (S1), providing vocal identity, pitch, pace and delivery without reusing the source words.
```

不得把参考母音里的原始台词写入目标视频。

---

## 8. FL2VA 最终 Prompt 模板

`FIRST_LAST_FRAME` 模式不使用 Ref2VA 六段式，使用官方 Base 格式：

```text
How the reference pictures align with the target video — Picture 1 (from Shot 1) aligns with the 0.00-second mark of the target video; Picture 2 (from Shot 1) aligns with the [duration formatted to 2 decimals]-second mark of the target video.

integrated_multimodal_description: [Shot 1] [style and initial composition from Picture 1]. The camera [movement, amplitude and speed] as [subject action evolves chronologically]. [Character, clothing, prop and scene identities remain consistent]. [Dialogue using speaker ID and <d> tags when applicable]. The motion progressively narrows the visual difference from Picture 2, and at [duration] seconds the subject reaches [final pose/state], matching Picture 2 in composition, position and scene state.

overall_soundscape: [ambient sound and physical action sounds].

non_diegetic_music: [instrumentation, tempo and volume development, or N/A].
```

要求：

- 首尾帧只有一张时，根据实际模式改为 I2VA 或 L2VA 官方对齐声明；
- 两张都有时才使用 FL2VA；
- 一般保持单镜头连续运动；
- 不要在视频 Prompt 中重新堆叠两张静态帧的完整描述；
- 重点描述从首帧到尾帧的连续物理路径；
- H3 会同时生成原生音频，因此必须填写声音段落，不能只写画面运镜。

---

## 9. AI 系统提示词拆分

不要继续用一条超长系统提示词同时处理所有视频模型和两种 H3 模式。建议新增配置：

```text
ai.prompt.minimax_h3_fl2va_system
ai.prompt.minimax_h3_fl2va_user
ai.prompt.minimax_h3_ref2va_system
ai.prompt.minimax_h3_ref2va_user
```

现有：

```text
ai.prompt.shot_prompt_derive_system
ai.prompt.shot_prompt_derive_user
```

保留为非 H3 模型的通用 fallback。

前端或后端增加目标提示词规格：

```text
promptTarget = MINIMAX_H3
```

系统根据：

```text
promptTarget + generationMode
```

选择对应模板。

如果项目当前所有视频工作流都以 H3 为主，可以暂时默认 `MINIMAX_H3`，但 DTO 中仍应保留该字段，避免以后接入 Kling、Runway、Sora 时再次重写同一个 Prompt。

---

## 10. DTO 修改

### 10.1 Java `ShotPromptDeriveDTO`

增加：

```java
private List<ShotRefImageDTO> refImages;
private List<ShotRefAudioDTO> refAudios;
private String promptTarget;
```

继续保留：

```java
private String generationMode;
```

### 10.2 前端 `ShotPromptDeriveDTO`

增加：

```ts
refImages?: ShotRefImage[]
refAudios?: ShotRefAudio[]
promptTarget?: 'MINIMAX_H3' | 'GENERIC'
```

`ShotPromptDeriveModal.vue` 的请求载荷必须发送当前分镜表单中的参考素材，而不是只依赖已落库数据，这样用户在保存分镜前也能预览正确 Prompt。

### 10.3 `ShotRefAudioDTO`

增加：

```java
private String usageMode;
private String language;
```

前端音频卡片增加“用途”选择。TTS 生成和角色声音样本导入流程应写入明确默认值，但允许用户修改。

---

## 11. Java 上下文组装

增加：

```text
buildReferenceManifest(dto)
buildH3MediaContext(manifest)
```

模型输入中增加一个明确区段：

```text
【MiniMax H3 有序参考素材清单】
Picture 1:
- referenceId: ...
- sourceType: SCENE
- entityName: 顶层办公室
- usageRole: SCENE_LAYOUT
- description: ...

Picture 2:
- sourceType: CHARACTER
- entityName: 林默
- usageRole: IDENTITY
- appearance: ...
- outfit: ...

Audio 1:
- characterName: 林默
- usageMode: VOICE_TIMBRE
- language: Chinese
- sourceText: ...
```

系统提示词必须要求模型：

- 只使用清单中实际存在的标签；
- 不得跳号；
- 不得生成不存在的 `<Picture N>` 或 `<Audio N>`；
- 同一标签在六个段落中含义不变；
- 人物、场景、道具文字描述必须写入 Prompt；
- 引用图提供视觉身份，文字描述明确需要保留的特征，两者不能互相替代。

---

## 12. Gateway 改造

### 12.1 停止追加旧版通用关系 Prompt

当请求声明：

```json
{
  "prompt_format": "MINIMAX_H3_REF2VA_V1"
}
```

Gateway 应把 `videoPrompt` 原样送入 H3 节点，不再调用当前：

```python
ReferenceRelationPromptBuilder.assemble_final_prompt(...)
```

否则 AI 已生成的官方六段式 Prompt 会再次被旧版中文关系模板包裹。

可以保留旧 Builder 兼容历史调用，但 H3 新调用链必须显式绕过。

### 12.2 上传全部参考音频

新增：

```python
uploaded_ref_audios: List[str]
```

按 ReferenceManifest 顺序上传所有有效音频，并连接：

```text
ref_audios.ref_audio_0
ref_audios.ref_audio_1
ref_audios.ref_audio_2
```

不要再只使用单个 `request.audio_url`。

### 12.3 保证媒体编号一致

上传失败时不能静默过滤后继续使用原 Prompt，因为后续素材编号会整体错位。

正确策略：

- 任一已被 Prompt 引用的媒体上传失败，整个请求失败；
- 返回具体失败的 `referenceId` 和期望标签；
- 不要在上传失败后自动重排 `<Picture N>` 或 `<Audio N>`。

### 12.4 限制校验

Gateway 和前端都校验：

- 图片 `<= 9`；
- 音频 `<= 3`；
- 每段音频 `2–15s`；
- 音频总长 `<= 15s`；
- 混合素材总数 `<= 12`；
- 参考模式存在音频时，至少存在一张参考图或一段参考视频；
- 输出时长为 `4–15s`；
- Prompt 长度不超过当前 H3 API/本地节点允许的上限。

本项目暂未支持参考视频时，只校验图片和音频即可，但 DTO/Manifest 设计应预留 `Video N`。

---

## 13. 结果校验

### 13.1 Ref2VA

校验六个段落存在且顺序正确：

```text
subject_definitions
summary
retention_analysis
detailed_description
overall_soundscape
non_diegetic_music
```

校验：

- 所有 `<Picture N>` 都能在 Manifest 找到；
- 所有 `<Audio N>` 都能在 Manifest 找到；
- 标签连续且不重复定义；
- 人物/场景参考图均在 `subject_definitions` 中建立关系；
- 有对白时存在 `(Sx)` 和 `<d>[Language] ...</d>`；
- `<d>` 内台词与用户原文完全一致；
- `DIALOGUE_REUSE` 与 `VOICE_TIMBRE` 使用不同的 retention marker；
- `detailed_description` 中包含镜头时长能够完成的动作过程；
- 场景、人物和道具文字描述没有因为存在参考图而被删除。

### 13.2 FL2VA

校验：

- 对齐声明是 Prompt 第一行；
- 首帧时间为 `0.00`；
- 尾帧时间等于有效视频时长并格式化为两位小数；
- 包含三个固定字段；
- 只有一张帧图时不错误使用双帧声明；
- 动作路径最终明确收敛到尾帧。

这些校验只检查结构、标签、字段和显式事实，不使用自然语言关键字猜谜判断画面质量。

---

## 14. 数据库存储策略

第一阶段不新增数据库列：

- `drama_shot.video_prompt` 存储最终 H3 Prompt；
- `ref_images_json` 继续存有序图片清单；
- `ref_audios_json` 增加 `usageMode` 和 `language` JSON 字段；
- `sys_config` 新增四条 H3 专用提示词配置。

建议将 Prompt 格式版本写入日志和渲染任务快照：

```text
MINIMAX_H3_FL2VA_V1
MINIMAX_H3_REF2VA_V1
```

如果后续需要可审计地复现每次标签与媒体顺序，再增加 `reference_manifest_json` 数据库列。本次可以先把 Manifest 放进渲染任务请求快照或日志，不必立即扩表。

数据库配置更新后，刷新或清除对应 Redis 缓存。

---

## 15. 测试计划

### 15.1 Java 单元测试

1. 一张场景图、一张人物图、一段音色音频：标签分别为 Picture 1、Picture 2、Audio 1；
2. 两个人物多张参考图：Subject 不串人，多个 Picture 可归属同一 Subject；
3. 没有场景参考图但有场景文字：Prompt 包含场景描述，不伪造 Picture 标签；
4. `VOICE_TIMBRE` 不复用参考音频原台词；
5. `DIALOGUE_REUSE` 保留原始目标台词并声明同步；
6. 中文对白原样出现在 `<d>[Chinese] ...</d>`；
7. FL2VA 尾帧时间与 duration 一致；
8. 引用不存在的 Picture/Audio 时校验失败。

### 15.2 Gateway 测试

1. 三张图片按顺序连接 `ref_image_0..2`；
2. 三段音频按顺序连接 `ref_audio_0..2`；
3. Prompt 中 Picture/Audio 编号与节点顺序一致；
4. 第二段音频上传失败时整个任务失败，不继续重排；
5. `prompt_format=MINIMAX_H3_REF2VA_V1` 时不追加旧版中文关系 Prompt；
6. 超过三段音频、总时长超过 15 秒、只有音频无视觉参考时拒绝请求。

### 15.3 前端验收

1. Prompt 生成弹窗能展示参考图片与音频的 H3 标签预览；
2. 音频卡片可选择用途；
3. 更换图片顺序后重新生成 Prompt，标签与新顺序一致；
4. 已生成 Prompt 后再次调整素材顺序，应提示“Prompt 标签已失效，需要重新生成”；
5. 所有雪花 ID 保持字符串，不调用 `Number()`。

### 15.4 人工生成验收

同一分镜至少执行：

- 纯 FL2VA；
- 场景图 + 单人物图 Ref2VA；
- 场景图 + 双人物图 Ref2VA；
- 人物图 + 音色参考；
- 人物图 + 最终 TTS 对白复用。

重点观察：

- 人物是否串脸、换装；
- 场景结构是否稳定；
- 道具数量和归属是否变化；
- 动作是否能在指定时长内完成；
- 对白是否原文一致；
- 音色参考和音频复用是否符合声明；
- 口型是否与复用对白同步；
- 最终提交给 ComfyUI 的 Prompt 是否与页面预览完全一致。

---

## 16. 推荐执行顺序

1. 为 `ShotPromptDeriveDTO` 增加 `refImages`、`refAudios`、`promptTarget`；
2. 为参考音频增加显式 `usageMode` 和 `language`；
3. 实现稳定有序的 `ReferenceManifest`；
4. 新增 H3 FL2VA/Ref2VA 四条系统配置；
5. 修改文本模型上下文和输出规范；
6. 增加 H3 Prompt 结构与标签校验；
7. 修改 Gateway，禁止新格式走旧版关系 Prompt Builder；
8. Gateway 支持最多三段参考音频的上传和节点连接；
9. 调整前端参考素材数量、时长和组合限制；
10. 更新 `init.sql` 和数据库配置并刷新 Redis；
11. 完成 Java、Gateway、前端构建测试；
12. 使用真实 MiniMax H3 工作流完成五类人工生成验收。

---

## 17. 最终决策建议

本次不要只修改数据库中的一段中文系统提示词。仅修改提示词无法解决素材编号、音频只接入一段、Gateway 二次追加旧格式和前端限制错误等问题。

正确改造边界是：

```text
AI 负责把明确的结构化事实转译为 H3 官方 Prompt 语义；
Java 负责构建 ReferenceManifest、提供完整人物/场景/道具描述并校验标签；
Gateway 负责严格按 Manifest 顺序上传媒体并原样提交最终 Prompt；
ComfyUI 负责执行 H3 FL2VA/Ref2VA 工作流。
```

这是保证参考图、场景描述、人物描述、音频关系和最终 MiniMax H3 理解结果一致的最小可靠架构。
