# AI 剧本智能拆解与并行分段解析架构 API 规范文档 (script-decompose-api)

> 基础路径：`http://127.0.0.1:8080`
> 统一返回结构：`R<T>` (`code`: 200, `msg`: "success", `data`: T)

---

## 1. 架构定位：整章并行分段解析 (Parallel Segmented Decomposition Architecture)

为解决超长小说章节解析时模型上下文退化、漏情节、镜头时长碎片化 (1~3s) 等问题，本系统采用 **Planner AI (1次宏观分段) + Story Normalizer × N + Worker AI × N + Java 确定性合并与规则体检**：

```text
一章小说全文 (3000~8000+ 字)
    ↓
1. Planner AI (全局仅 1 次 AI 调用): 负责剧情事件分段、角色/道具/场景提取，并为所有 Segment 一次生成简短的 previousStateHint、currentEventHint、nextEventHint
    ↓
2. Java 本地边界自愈与引用校验 (0 次额外 AI 调用): Planner 在同一次请求中完成角色身份判断；Java 校验返回的角色 ID 属于当前项目并处理现有资产引用
    ↓
3. Story Normalizer × N → Worker AI × N: 沿用虚拟线程池与 Semaphore(maxConcurrency=3)；每段先独立标准化连续事件，再拆 ShotGroup/Shot。不同段并行，无前后段 Normalizer 结果依赖。Normalizer 失败时该段 Worker 使用原文；Worker 保留原文为最高事实来源
    ↓
4. Java 确定性合并 (ShotMergeService, 0 次 AI 调用): 严格按 segment.sequence 排序，重排 Scene/ShotGroup/Shot 全局连续编号，透传剧本字段
    ↓
5. Java 连续性体检与防碎片统计 (ContinuityRuleEngine + ShotDurationRule, 0 次 AI 调用): 校验 10 大确定性连续性规则与镜头时长，碎片率 > 30% 预警
    ↓
6. 后续专属视觉总监 AI / ShotAiVisualPlanService: 以小说原文与镜头剧本 (scriptContent) 为双重权威参考，精准推导生成高质量首帧与视频 Prompt
    ↓
7. 级联入库与生图/调度 (Drama Episode Scene ShotGroup Shot 4层大纲持久化，含 script_content 存储)
```

`StorySegment.normalizedContent` 与 Planner 连续性提示保存在现有任务预览/Redis 草稿 JSON 中，原文仍保存在父任务请求，单段重试时按 offset 恢复；没有新增业务表或数据库字段。旧草稿中 `normalizedContent` 为空时使用原文。Normalizer 使用独立系统提示词配置键 `ai.prompt.story_normalizer_system`，未配置时使用代码默认值。它只显式化原事件成立所需的空间、动作和道具状态，不创作新剧情，也不设计镜头或摄影。Worker Skill 加载及后续 Cinematography/Prompt AI 仍按原流程运行。

标准化模型的原始流式片元作为 `channel_chunk`（通道 `NORMALIZER_SEG001` 等）写入现有 Redis AI 事件日志，并通过任务中心同一条 WebSocket 推送；`normalizer_status` 事件携带分段 `channel` 和 `RUNNING` / `SUCCESS` / `FALLBACK` 状态。前端为每段提供标准化与 Worker 独立视口，并在结果卡片展示 `normalizedContent`。系统配置初始化 SQL 已包含标准化提示词；已有数据库执行 `sh docker/mysql/migrations/apply-20260927-story-normalizer-prompt.sh`，不会覆盖现有同键配置。

---

## 2. 接口详细列表 (`ScriptDecomposeController` / `/script`)

### Planner / Worker AI Skills

拆解请求可选 `skillPolicy`，Planner 与所有 Worker 独立配置。Planner 必须加载 `character-disambiguation`，请求中的 Planner Skill 会在此基础上继续追加；Worker Skill 仍独立配置：

```json
"skillPolicy": {
  "planner": { "allowDynamicLoad": true, "requiredSkillNames": ["story-structure"] },
  "worker": { "allowDynamicLoad": true, "requiredSkillNames": ["shot-writing"] }
}
```

`character-disambiguation` 与 `requiredSkillNames` 中的其他必用 Skill 会在 AI 调用前按父任务版本快照加载 `SKILL.md` 并注入对应阶段。缺少、停用或无法加载该 Skill 时，Planner 请求直接失败，不会回退到 Java 身份推断。`allowDynamicLoad` 打开后，模型可通过 `load_skill(name)` 按需调用同一快照目录中的其他技能；模型明确不支持 Tool Calling 时，启动任务返回参数错误。Worker 每个分段及每次自动重试使用独立工具会话。

新增的 `character-disambiguation` 包位于 `src/main/resources/skills/character-disambiguation/`，可通过系统「AI Skills」页面上传同目录下的 ZIP 包启用。已有数据库执行 `src/main/resources/sql/upgrade_planner_character_disambiguation.sql` 后，Planner 系统提示词会使用 Skill 职责说明并清理旧的独立角色消歧配置。

Worker 默认系统提示词与 `init.sql` 初始配置只规定原文忠实、镜头结构和字段契约；提供 `load_skill` 且启用目录包含 `camera-direction` 时，要求模型先加载该 Skill，由它指导景别、运镜、相邻镜头衔接与可行时长。未启用 Skill 时，Worker 根据原文动作和对白选择简洁、可完成的镜头，不套用固定秒数。已有数据库中的 `ai.prompt.shot_worker_system` 需要在系统参数配置中同步更新，运行时不覆盖已保存的自定义提示词。

当前 `load_skill` 只返回 Skill 的 `SKILL.md` 正文；`camera-direction` 中列出的 `references/` 文件尚未提供给 Worker，详细参考资料不会随本次调用自动加载。

任务推送 `skill_event`，包含 `stage`、`segmentId`、`attempt`、`name`、`versionId`、`version`、`contentHash`、`source` (`REQUIRED` / `TOOL`) 和 `status`，不包含 Skill 正文。Worker 自动重试会推送 `channel_reset` 清除该分段先前的部分流式输出。最终结果的 `skillEvents` 保存实际加载记录。人工单段重试的 `WorkerRetryDTO.skillPolicyOverride` 可覆盖 Worker 策略；未填写则继承父任务设置，仍使用父任务版本快照。

### 2.1 一键智能剧本拆解 (同步模式)
- **URL**: `POST /script/decompose`
- **说明**: 调度 Planner AI → Parallel Workers → Java Merge → Continuity Rule Engine → PromptBuilder 生成完整结构化结果。
- **Body**: `ScriptDecomposeRequestDTO`
```json
{
  "providerId": 1,
  "modelCode": "deepseek-chat",
  "rawText": "苏氏集团顶层会议室，气氛剑拔弩张...",
  "dramaId": null,
  "targetEpisodes": 1,
  "targetDurationPerEpisode": 90,
  "aspectRatio": "9:16",
  "stylePreset": "cinematic-realism",
  "pacingPreset": "AUTO"
}
```
- `pacingPreset` 默认 `AUTO`；省略或传空值时，Worker 不附加剪辑节奏与镜头时长偏好。可显式选择 `STANDARD`、`CINEMATIC_LONG` 或 `FAST_PACED`。
- **返回结构 (`ScriptDecomposeResultVO`)**:
```json
{
  "dramaTitle": "决裂时刻",
  "genre": "URBAN_ABILITY",
  "synopsis": "故事大纲梗概...",
  "segments": [
    {
      "id": "SEG001",
      "sequence": 1,
      "startOffset": 0,
      "endOffset": 1500,
      "title": "会议室对峙",
      "summary": "苏明宇逼迫苏清雪交出总裁职位",
      "characterIds": ["苏清雪", "苏明宇"],
      "locationIds": ["顶层会议室"],
      "narrativePurpose": "建立开场核心戏剧冲突"
    }
  ],
  "characters": [
    {
      "name": "苏清雪",
      "canonicalName": "苏清雪",
      "identityStatus": "CONFIRMED",
      "roleType": "PROTAGONIST",
      "gender": "FEMALE",
      "appearancePrompt": "1woman, 24yo, elegant, red eyes",
      "outfitPrompt": "wearing white corporate suit"
    }
  ],
  "scenes": [
    {
      "sceneName": "顶层会议室",
      "sceneType": "INDOOR",
      "timeOfDay": "DAY",
      "scenePrompt": "high-end corporate boardroom"
    }
  ],
  "episodes": [
    {
      "episodeNo": 1,
      "title": "第1集",
      "scenes": [
        {
          "sceneNo": 1,
          "sceneName": "顶层会议室",
          "shotGroups": [
            {
              "groupNo": 1,
              "name": "苏明宇逼宫",
              "purpose": "展现压迫感",
              "continuityLevel": "STRICT",
              "shots": [
                {
                  "shotNo": 1,
                  "shotName": "S01-01",
                  "groupId": "G001",
                  "shotType": "AUTO",
                  "cameraMovement": "AUTO",
                  "shotTypeLocked": false,
                  "cameraMovementLocked": false,
                  "duration": 5.5,
                  "scriptContent": "苏明宇冷笑着将手中厚重的文件重重拍在红木会议桌上，居高临下直逼对面的苏清雪。",
                  "actionDescription": "苏明宇将财务报表重重摔在桌上，冷笑逼迫",
                  "dialogueSpeaker": "苏明宇",
                  "dialogue": "苏清雪，三天内拿不出五千万，就交出总裁位置！",
                  "characterNames": ["苏明宇", "苏清雪"],
                  "prompt": "(masterpiece), cinematic film still, modern luxury office...",
                  "negativePrompt": "blurry, low quality, deformed hands",
                  "continuity": {
                    "fromPrevious": false,
                    "importantState": {
                      "position": "conference_desk",
                      "pose": "standing"
                    }
                  }
                }
              ]
            }
          ]
        }
      ]
    }
  ],
  "fragmentationStats": {
    "totalShots": 15,
    "shortShots": 1,
    "normalShots": 14,
    "longShots": 0,
    "averageDuration": 4.8,
    "shortShotRatio": 0.067,
    "isHighFragmentation": false,
    "warningMessage": null
  }
}
```

---

### 2.2 流式 (SSE) 一键智能剧本拆解 (实时打字机与并发进度模式)
- **URL**: `POST /script/decompose/stream`
- **Content-Type**: `application/json`
- **Accept / Produces**: `text/event-stream`
- **说明**: 采用 SSE Server-Sent Events 实时推送各 Stage 执行进展与 Worker 并发状态，最终通过 `event: result` 推送强类型 `ScriptDecomposeResultVO` 对象。

---

### 2.3 确认并一键持久化入库
- **URL**: `POST /script/commit`
- **说明**: 将用户确认或修改后的数据级联持久化至 `drama`、`drama_episode`、`drama_scene`、`drama_shot_group`、`drama_shot`、`res_character`、`res_scene`。支持幂等性更新与防重。
- **Body**: `ScriptDecomposeCommitDTO`
- **返回**: `R<Long>` (持久化后的短剧 ID)

---

### 2.4 单集/单场深度细化拆解
- **URL**: `POST /script/decompose/episode`
- **Body**: `ScriptDecomposeRequestDTO`
- **返回**: `R<DecomposedEpisodeVO>`

---

### 2.5 AI 流水线任务列表查询
- **URL**: `GET /script/task/list`
- **Query**: `dramaId`, `targetId`, `status`, `taskType`
- **返回**: `R<List<AiTaskVO>>`

---

### 2.6 后台拆解与 Redis 草稿恢复

- `POST /script/decompose/task`：立即返回任务 ID，Planner 和 Worker 在服务端继续执行；关闭弹窗或浏览器不取消任务。
- `GET /script/task/history`：列出 Redis 中的拆解任务与旧版 MySQL 历史任务。
- `GET /script/task/{taskId}/draft`：恢复原文、模型、样式和 Skill 选择。
- `GET /script/task/{taskId}/preview`：读取完整合并预览；任务未完成时可订阅任务中心 WebSocket 的 `AI_SUBSCRIBE`，从头重放事件后继续接收实时进度。
- `DELETE /script/task/{taskId}`：删除 Redis 草稿、子任务与事件记录。
- `POST /ai/tasks/worker/{taskId}/retry`：Worker 局部重试使用 Redis 子任务与 7 天事件记录，更新同一个拆解草稿；任务中心仍以拆解主任务为恢复入口。

未确认前，原文、Planner/Worker 结果、任务元数据、进度事件与合并预览均在 Redis；业务实体只在 `POST /script/commit` 后写入 MySQL。草稿与事件保留 7 天，自最近一次写入续期。服务进程重启后可恢复已有数据和进度记录，但正在运行的虚拟线程不会自动重新执行。
