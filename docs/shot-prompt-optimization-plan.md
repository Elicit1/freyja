# 分镜精细配置 AI 提示词优化实施方案

## 1. 背景与目标

当前“分镜精细配置 → AI 智能生成提示词”功能对应前端组件 `ShotPromptDeriveModal.vue`，后端入口为：

```text
POST /drama/shot/derive-prompt-stream
```

运行时系统提示词来自系统配置：

```text
ai.prompt.shot_prompt_derive_system
ai.prompt.shot_prompt_derive_user
```

本方案目标：

1. 修复前后端字段不一致导致创作者特别指令丢失的问题；
2. 修复 AI 提供商雪花 ID 被 JavaScript `Number` 转换的问题；
3. 补齐说话人、情绪、音效等模型上下文；
4. 明确 `FIRST_LAST_FRAME` 与 `REFERENCE_MODE` 两种模式的生成契约；
5. 强化首帧、尾帧与视频动态提示词的时态隔离；
6. 统一数据库、初始化 SQL 与 Java fallback，避免配置漂移；
7. 增加模式校验与自动化测试。

本次不需要新增数据库表或字段。数据库变更仅涉及 `sys_config` 中两条配置的内容更新。

---

## 2. 已确认的问题

### 2.1 创作者特别指令没有传入后端

前端发送字段：

```ts
instruction: instruction.value
```

后端 DTO 接收字段：

```java
private String userInstruction;
```

Jackson 无法把 `instruction` 自动映射到 `userInstruction`，因此该字段会被静默忽略。

### 2.2 雪花 ID 存在精度损失风险

前端存在：

```ts
providerId.value = Number(providers.value[0].id)
```

所有业务 ID 必须保持原始字符串，不得调用 `Number()`、`parseInt()` 或一元 `+`。

### 2.3 数据库中的生效提示词落后于代码功能

当前数据库配置 `ai.prompt.shot_prompt_derive_system` 仍是旧版“双轨”描述，只完整规定了：

- `firstFramePrompt`
- `videoPrompt`
- `negativePrompt`

但当前页面和 DTO 已经需要：

- `prompt`
- `firstFramePrompt`
- `endFramePrompt`
- `videoPrompt`
- `negativePrompt`

数据库配置会覆盖 Java 中的 fallback，因此只修改 Java 常量不会改变当前运行效果。

### 2.4 两种生成模式没有在数据库提示词中分流

现有业务支持：

- `FIRST_LAST_FRAME`：生成首帧、尾帧、视频动态和负向提示词；
- `REFERENCE_MODE`：人物与场景由参考图锁定，只生成视频动态和负向提示词。

当前数据库提示词没有定义两种模式的差异。模型仍可能在 `REFERENCE_MODE` 下生成首尾帧，后端随后再强制清空，造成 Token 浪费和指令干扰。

### 2.5 部分有效上下文没有进入模型输入

需要补齐：

- `dialogueSpeaker`：前端有字段，后端 DTO 缺失；
- `soundEffect`：后端 DTO 已有字段，但 `buildShotSpec()` 没有使用；
- `emotionPrompt`：角色引用已包含字段，但 `buildCharacterContext()` 没有使用。

### 2.6 当前提示词缺少可执行约束

现有提示词大量使用“工业级”“专业”“高质”等抽象要求，但没有充分规定：

- 信息优先级；
- 首帧和尾帧的静态画面组成顺序；
- 视频提示词只描述变化过程；
- 参考图模式不得重复外貌和服装；
- 负向词不得否定镜头必需元素；
- 不同字段的语言要求；
- 无事实依据时必须返回 `null`。

---

## 3. 实施范围

至少检查和修改以下文件：

```text
front/src/views/drama/components/ShotPromptDeriveModal.vue
front/src/types/drama.d.ts
front/src/api/drama.ts
src/main/java/com/astra/freyja/dto/drama/ShotPromptDeriveDTO.java
src/main/java/com/astra/freyja/service/impl/ShotAiVisualPlanServiceImpl.java
src/main/resources/sql/init.sql
src/test/java/com/astra/freyja/service/
```

同时更新数据库 `sys_config` 中：

```text
ai.prompt.shot_prompt_derive_system
ai.prompt.shot_prompt_derive_user
```

不要误改 `ai.prompt.shot_visual_plan_system`。它服务于旧的 `/ai-visual-plan` 调用链，不是本弹窗流式生成接口的主要系统提示词。

---

## 4. 前端修改

### 4.1 统一特别指令字段

在 `front/src/types/drama.d.ts` 中，将：

```ts
instruction?: string
```

改为：

```ts
userInstruction?: string
```

在 `ShotPromptDeriveModal.vue` 请求载荷中，将：

```ts
instruction: instruction.value,
```

改为：

```ts
userInstruction: instruction.value.trim() || undefined,
```

### 4.2 修复 ID 类型

提供商 ID 类型改为：

```ts
const providerId = ref<string | number>()
```

加载默认提供商时使用：

```ts
providerId.value = String(providers.value[0].id)
```

选择项的 key/value 使用：

```vue
<el-option
  v-for="p in providers"
  :key="String(p.id)"
  :label="p.providerName"
  :value="String(p.id)"
/>
```

`handleProviderChange`、`aiProviderApi.getModelList` 和相关类型应接受 `string | number`，不得把 ID 转为 JavaScript `number`。

检查本调用链中的 `shotId`、`dramaId`、`episodeId`、`sceneId`、`resSceneId`、`characterId`、`outfitId`、`propId`，确保没有新增任何数字强制转换。

### 4.3 清洗空值

请求载荷中的可选字段使用 `undefined`，不要发送空字符串、`0` 或 `NaN`。建议增加局部工具：

```ts
const optionalText = (value?: string) => value?.trim() || undefined
```

仅用于空值清洗，不得用它做自然语言推断。

---

## 5. 后端 DTO 与上下文修改

### 5.1 增加对白说话人

在 `ShotPromptDeriveDTO` 增加：

```java
/** 对白说话人 */
private String dialogueSpeaker;
```

### 5.2 补齐镜头规格上下文

在 `buildShotSpec()` 中加入：

```java
if (StringUtils.isNotBlank(dto.getDialogueSpeaker())) {
    sb.append("- 对白说话人: ").append(dto.getDialogueSpeaker()).append("\n");
}
if (StringUtils.isNotBlank(dto.getSoundEffect())) {
    sb.append("- 音效与环境声: ").append(dto.getSoundEffect()).append("\n");
}
```

保持现有 `scriptContent`、`actionDescription`、`dialogue`、`voiceover` 和 `duration`。

不要在 Java 中根据动作、台词或音效关键字猜测画面语义。

### 5.3 补齐角色情绪上下文

在 `buildCharacterContext()` 中加入：

```java
if (StringUtils.isNotBlank(ref.getEmotionPrompt())) {
    sb.append("  * 本镜情绪与微表情: ")
            .append(ref.getEmotionPrompt())
            .append("\n");
}
```

角色上下文建议保持以下顺序：

1. 正式名称与角色定位；
2. 外貌视觉 SSOT；
3. 本镜服装；
4. 本镜即时动作；
5. 本镜情绪与微表情；
6. 画面站位。

### 5.4 调整剧情原文的权重

推荐即时方案：

- 当 `scriptContent` 非空时，仅传入 `episodeSummary`，不要再次附加整集 `episode.scriptContent`；
- 当 `scriptContent` 为空时，才允许把整集原文作为弱参考；
- 系统提示词明确规定当前镜头事实优先于剧集背景。

如果业务确认必须始终提供原文，后续可增加分镜级 `sourceExcerpt`，由上游 Planner/Worker 结构化提供。本次不建议新增字段或使用 Java 关键字截取原文。

---

## 6. 系统提示词替换稿

配置键：

```text
ai.prompt.shot_prompt_derive_system
```

建议完整替换为：

```text
你是影视视觉导演、分镜设计师和生成式影像提示词工程师。

你的任务是根据输入的当前分镜事实，为指定生成模式输出结构化提示词。不得补造输入中不存在的角色、服装、道具、空间关系、动作、情绪、光源或剧情结果。

【事实优先级】
1. 当前镜头剧本 scriptContent；
2. 当前镜头动作、对白、说话人、音效和时长；
3. 已绑定的角色、服装、场景和道具资产；
4. 创作者特别指令；
5. 剧集摘要或原文背景。

发生冲突时必须服从优先级更高的事实。资产中的人物外貌、服装和道具材质视为身份一致性事实，不得擅自改写。

【视觉风格唯一来源】
stylePreset 和 styleTone 是唯一视觉风格来源。
上游文学文本只决定“画什么”，stylePreset/styleTone 决定“以什么形式画”。
不得从小说中的比喻性文字引入额外的 anime、comic、watercolor、oil painting、pixel art、3D render 等冲突风格。

【静态帧与动态视频严格分离】
- firstFramePrompt 和 endFramePrompt 只能描述一个可被静态图像呈现的瞬间。
- 静态帧中禁止出现 camera pushes、pans、tilts、gradually、suddenly、starts to、continues to、flickering 等时间过程或运镜表述。
- prompt/videoPrompt 只描述镜头运动、人物动作变化、道具运动、环境动态和视听节拍，不重复长篇人物外貌与服装。
- 禁止使用 masterpiece、best quality、8k、ultra detailed 等无具体视觉含义的质量词。

【FIRST_LAST_FRAME 模式】
firstFramePrompt：
描述 t=0 的起始静态画面，按以下顺序组织：
视觉风格与媒介；景别和视角；主体身份与画面位置；起始姿态、视线和表情；服装与关键道具；场景空间；主光方向、色温、景深和构图。
必须完整体现绑定资产，但不得添加未提供的外貌和服装。

endFramePrompt：
描述镜头结束时的静态画面。
保持同一角色、服装、场景、道具和视觉风格，只描述动作结束后的明确姿态、位置、视线、表情以及必要的环境终态。
不得把动作过程写入尾帧。

prompt 与 videoPrompt：
二者内容必须一致。
描述从首帧到尾帧的连续变化，包括摄影机轨迹、主体动作、道具交互、环境动态和动作节奏。
动作必须能在给定 duration 内完成，禁止加入输入中不存在的新动作或剧情。

【REFERENCE_MODE 模式】
人物、服装、场景和道具外观已由参考图锁定。
firstFramePrompt 和 endFramePrompt 必须为 null。
prompt 与 videoPrompt 必须一致，只描述：
摄影机运动；主体动作演进；表情变化；人物交互；道具运动；环境动态；对白或音频节拍。
不要重复人物五官、发型、服装和场景材质，避免与参考图冲突。

【负向提示词】
negativePrompt 只包含：
常见生成缺陷、动态畸变、身份漂移、肢体异常、文字水印，以及与当前镜头明确冲突的元素。
不得把当前镜头需要出现的角色、服装、道具、天气或视觉风格写入负向词。
避免堆叠重复同义词。

【语言规范】
firstFramePrompt、endFramePrompt、prompt、videoPrompt、negativePrompt 使用英文。

【输出要求】
严格按照 JSON Schema 输出。
不得输出 Markdown、解释、推理过程或额外字段。
没有事实依据的可选字段使用 null，不得猜测。
```

---

## 7. 用户提示词模板替换稿

配置键：

```text
ai.prompt.shot_prompt_derive_user
```

建议替换为：

```text
请严格依据以下结构化事实生成当前单个分镜的提示词。
只处理当前分镜，不得将剧集其他镜头的事件、动作或人物状态混入本镜。

${DRAMA_CONTEXT}
${SHOT_SPEC}
${SCENE_CONTEXT}
${CHARACTER_CONTEXT}
${PROP_CONTEXT}
${USER_INSTRUCTION}

【输出格式规范（严格遵守 JSON Schema）】
${OUTPUT_FORMAT}
```

占位符名称不得改变，除非同步修改 `resolveTemplate()` 的变量映射。

---

## 8. 后端结果校验

模型返回并完成 JSON 解析后，按模式进行字段级校验。

### 8.1 `FIRST_LAST_FRAME`

必须满足：

- `firstFramePrompt` 非空；
- `endFramePrompt` 非空；
- `prompt` 或 `videoPrompt` 至少一个非空；
- 一个动态字段为空时，用另一个动态字段补齐；
- 最终保证 `prompt` 与 `videoPrompt` 一致。

不满足时返回明确错误，不要把不完整结果静默回填到分镜。

### 8.2 `REFERENCE_MODE`

必须满足：

- `prompt` 或 `videoPrompt` 至少一个非空；
- 最终保证 `prompt` 与 `videoPrompt` 一致；
- `firstFramePrompt` 和 `endFramePrompt` 强制设为 `null`；
- 如果模型仍返回首尾帧，记录一次警告日志，便于后续评估系统提示词服从度。

### 8.3 禁止增加的校验

不得在 Java 中编写自然语言关键字、正则或 `contains()` 猜谜逻辑来判断画面是否合理。Java 只负责：

- 字段完整性；
- 模式契约；
- 空值规范；
- JSON 结构；
- 必要的长度上限；
- 持久化前的数据清洗。

---

## 9. 数据库、初始化脚本与缓存

必须同步三处内容：

1. 当前数据库 `sys_config.config_value`；
2. `src/main/resources/sql/init.sql`；
3. `ShotAiVisualPlanServiceImpl.DEFAULT_PROMPT_DERIVE_SYSTEM_PROMPT` 和用户模板 fallback。

推荐通过现有系统配置服务/API更新数据库，而不是按固定 ID 更新。应以 `config_key` 为唯一定位条件：

```text
ai.prompt.shot_prompt_derive_system
ai.prompt.shot_prompt_derive_user
```

不要依赖 `id=23` 或 `id=27`，不同环境的主键可能不同。

配置更新后需要清除 Redis 缓存：

```text
freyja:config:ai.prompt.shot_prompt_derive_system
freyja:config:ai.prompt.shot_prompt_derive_user
```

也可以调用：

```text
POST /system/config/refresh-cache
```

注意：如果直接执行 SQL 更新而不清理缓存，运行中的后端可能继续使用旧提示词。

---

## 10. 自动化测试

建议新增 `ShotAiVisualPlanServiceTest`，至少覆盖以下测试。

### 10.1 请求上下文测试

验证最终用户提示词包含：

- `scriptContent`；
- `dialogueSpeaker`；
- `dialogue`；
- `voiceover`；
- `soundEffect`；
- `emotionPrompt`；
- `userInstruction`；
- 角色、服装、场景和道具资产信息。

### 10.2 首尾帧模式测试

模型返回完整 JSON 时：

- 保留 `firstFramePrompt`；
- 保留 `endFramePrompt`；
- `prompt` 与 `videoPrompt` 对齐；
- 结果能够正常发送 SSE `result` 事件。

缺少首帧、尾帧或动态提示词时，应返回明确错误。

### 10.3 参考图模式测试

即使模型错误返回首尾帧：

- 最终 `firstFramePrompt == null`；
- 最终 `endFramePrompt == null`；
- 保留并对齐 `prompt/videoPrompt`。

### 10.4 前端测试或静态检查

至少使用代码搜索确认本调用链不存在：

```text
Number(providerId)
Number(shotId)
parseInt(...Id)
+id
```

并确认请求 JSON 中发送的是 `userInstruction`，不是 `instruction`。

---

## 11. 构建验证

后端使用 JDK 26：

```powershell
$env:JAVA_HOME="F:\program_file\jdk26"
.\mvnw.cmd test
```

前端：

```powershell
$env:PATH="D:\program_file\mvn\nvm\v24.9.0;$env:PATH"
Set-Location front
npm run build
```

如果只执行相关后端测试：

```powershell
$env:JAVA_HOME="F:\program_file\jdk26"
.\mvnw.cmd test -Dtest=ShotAiVisualPlanServiceTest
```

---

## 12. 人工验收标准

准备同一个有角色、服装、场景、道具、对白和动作的分镜，分别验证两种模式。

### 12.1 首尾帧模式

- 首帧只描述动作开始前或刚开始的静态瞬间；
- 尾帧只描述动作完成后的静态终态；
- 两帧角色身份、服装、场景和道具保持一致；
- 视频提示词描述首帧到尾帧之间的运动过程；
- 视频提示词没有重复长篇外貌和服装；
- 创作者特别指令能明显影响输出；
- 没有 `masterpiece`、`best quality`、`8k` 等空泛标签。

### 12.2 参考图模式

- 首帧和尾帧字段为空；
- 视频提示词聚焦运镜、动作、表情、交互和节拍；
- 不重复人物五官、发型、服装和场景材质；
- 不新增参考图中没有依据的身份、道具或动作。

### 12.3 数据安全

- 浏览器请求中的所有雪花 ID 保持字符串；
- 后端可以正常反序列化字符串形式 Long；
- 不出现因低位精度丢失导致的 404；
- 空值使用 `undefined`/`null`，不发送空字符串、`0` 或 `NaN` 作为关联 ID。

---

## 13. 推荐执行顺序

1. 修复前端 `userInstruction` 字段和雪花 ID 类型；
2. 增加后端 `dialogueSpeaker` 并补齐上下文；
3. 更新 Java 系统提示词与用户模板 fallback；
4. 更新 `init.sql`；
5. 更新数据库中两个 `sys_config` 配置；
6. 清理/刷新 Redis 配置缓存；
7. 增加后端结果契约校验；
8. 增加自动化测试；
9. 执行 Maven 测试和前端构建；
10. 按两种生成模式完成人工验收。

完成后，应在交付说明中列出数据库配置键的新值、缓存刷新结果、测试结果，以及是否发现仍在使用旧 `/ai-visual-plan` 调用链的页面入口。

---

## 14. “API 自动生成 + 外部 AI 手工生成”双通道架构

为了摆脱对项目内部付费 API 的强依赖，系统将分镜提示词生成演进为双通道架构：

### 14.1 双通道对比

| 特性 | 通道 A: API 自动生成 | 通道 B: 外部 AI 手工生成 (零 API 费用) |
| :--- | :--- | :--- |
| **适用场景** | 追求效率、一键自动流水线 | 无需配置 API Key、利用外部 ChatGPT/Claude/Gemini |
| **API 调用** | 系统内部调用 Spring AI `ChatModel.stream` | 零本地模型调用，不产生 API 费用 |
| **Prompt 规范** | MiniMax H3 官方六段式 / 首尾帧规范 | 100% 完全相同（统一调用 `buildPromptPackage`） |
| **多模态附件** | 绑定物理 Reference Manifest 槽位编号 | 提供 `<Picture 1..N>` 与 `<Audio 1..N>` 上传建议顺序清单 |
| **结果清洗与校验** | `validateAndNormalize` 统一校验与归一化 | 100% 相同清洗与校验（去除 Markdown 围栏、超界检查） |
| **上下文指纹** | 支持 SHA-256 上下文指纹比对 | 比对 `contextFingerprint`，发生篡改时给出黄色预警 |

两种 H3 模式的 `buildPromptPackage` 会在 `ai.prompt.minimax_h3_*_system` 应用编排契约之后追加 H3 Skill 前置协议。API 通道只追加当前启用 Skill 的目录，并要求模型先主动调用 `load_skill(name="h3-prompt-writing")`；Skill 正文负责 MiniMax H3 官方 Prompt 语义与格式，SysConfig 负责 JSON 外壳、分镜事实、资产边界、DirectorPlan 和台词原文等应用约束。两者发生冲突时不并列执行，按协议裁决；Skill 版本快照和内容哈希会参与 `contextFingerprint`，外部 AI 粘回结果时可以识别 Skill 已发生变化。

### 14.2 核心接口
- `POST /drama/shot/derive-prompt-package`: 导出完整任务 Prompt 包（包含 `systemPrompt`, `userPrompt`, `combinedPrompt`, `contextFingerprint`, `referenceManifest`）；
- `POST /drama/shot/parse-derived-prompt`: 外部 AI 粘回文本结构解析、Markdown 清洗与合规性校验。
