# 统一架构升级说明

## 能力入口

- Agent 和 MCP 的工具发现、名称、Schema、启用状态和执行版本统一来自已发布的 AI 能力目录。
- MCP SDK 不再维护第二份静态工具清单，也不再通过增删 SDK 工具发布权限变更。
- MCP 服务总开关、令牌、读写权限、项目范围和 Origin 仍由服务配置管理；工具开关提交到能力平台。历史配置里的工具映射只用于首次导入与历史格式读取，不能覆盖已发布目录。
- 人物、造型、场景、道具和分镜提示词生成必须具备 Skill 服务依赖；分镜后台提示词任务还要求能力规则、任务持久化及事件存储依赖。缺少依赖时不能启动降级生成。

## API 与类型调整

- 人物造型统一使用 `ResCharacterLook`、`ResCharacterLookMapper`、`ResCharacterLookService` 和 `/res/character/{id}/looks`、`/res/character/look/**`。移除 `/res/character/outfit/**` 门面。
- 角色列表响应使用 `looks`、`defaultLook`、`lookCount`；默认造型输入使用 `defaultLookName`、`defaultLookPrompt`，造型名称使用 `lookName`，造型参考图使用 `lookReferenceImageUrl`。服装描述字段 `outfitPrompt` 保留其业务含义。
- 资产生图造型目标类型统一为 `CHARACTER_LOOK`，不再接受 `CHARACTER_OUTFIT` / `OUTFIT`。
- 造型提示词请求和响应为 `LookPromptDeriveDTO/VO`，前端调用 `deriveLookPrompt*`。
- 渲染提交响应为 `RenderSubmissionVO`、`RenderSubmissionStatus`，与具体提供商解耦。删除无调用的 Java ComfyUI 直连 DTO、任务实体和前端 `/v1/render/comfy/**` 请求模块。FastAPI 网关与 GPU ComfyUI 节点的连接仍是现行渲染架构。
- 视频后处理必须提交模型中心 `modelId`；删除 `providerId + modelCode` 选择模型的旧入口。结果仍包含已解析的模型代码快照。
- Agent 形象统一使用宠物精灵图配置。旧 `stateMedia` 仅在历史 JSON 读取时忽略，不再作为配置字段返回或使用。

## 历史数据

前后端应同步升级。启动时自动执行可重复的 `src/main/resources/sql/migrate_unified_shot_script.sql`，失败则阻止应用完成启动：

1. 将旧分镜剧本、说话人、对白、旁白及音频 URL 备份到 `drama_shot_legacy_script_backup`。
2. 将旧对白、旁白按明确字段合入 `script_content`，清空已归档的旧字段；不从自然语言猜测角色或动作。
3. 业务实体、导演输入及 API 只读取统一剧本，不再在编辑和保存过程中触发迁移。

升级前保留正常的数据库备份，并确保数据库账号可以创建备份表、读取及更新分镜。现有 Docker 数据升级也会执行同一脚本，重复执行不会重复追加对白或旁白。未删除历史数据列与备份表。

不可变的拆解草稿和提示词任务快照仍通过集中格式转换读取旧字段，包括 Outfit 到 Look 的名称转换；转换不修改原始快照。历史任务枚举和旧密钥解密仍用于读取既有记录，不能在未迁移数据时删除。

## 任务失败语义

SQL 任务插入、查询及状态保存失败时明确返回持久化错误，不生成时间戳任务 ID，不返回虚构的重试状态。不存在的任务不能重试。显式使用 DraftStore 的拆解草稿继续使用其持久化机制，不作为 SQL 异常后的内存兜底。

本次代码修改没有连接或迁移业务数据库；迁移在部署并启动新后端时执行。回归使用 H2 数据库验证备份、合并和重复执行。


## 第二轮运行时清理

- Planner/Worker 必须注入 `ScriptSkillRuntime`；拆解服务和 AI 任务服务必须具备草稿存储、任务服务与事件日志。移除无任务执行的构造器及缺少草稿存储时另存 SQL 请求/结果的分支。读取既有 SQL 历史任务仍受支持。
- 跨功能事件日志更名为 `AiTaskEventStore`，已有 Redis Key 保持兼容，任务重放与恢复无需清空数据。
- 配音设计和母音归档收拢到 `DubbingService`，删除 `CharacterVoiceService` 及旧 `/res/character/{id}/voice/design`、`/res/character/voice/preview`、`/ai/tasks/character/{id}/voice-design`、`/ai/tasks/voice-preview` 入口。工作台继续使用 `/dubbing/**`；已发布 `design_character_voice` 工具调用配音中心服务，保留试听和幂等语义。
- 资产生图只通过 `/ai/tasks/asset-image` 提交、通过统一任务中心恢复；删除 `/assets/image-generation/submit` 和 `/assets/image-generation/task/{taskId}` 及内存任务仓库。`/assets/image-generation/apply` 继续负责应用产物。删除无效的 `mode`、`workflowTemplateId`、`steps`、`strength` 入参，模型解析失败时停止提交渲染。
- 删除空的前端提示词轮询方法和重复资产生图轮询。参考图支持由模型声明的类型决定，不再猜测模型名。
- 删除 `promptTarget`、场景 `lightingPrompt`、统计 `isHighFragmentation/warningMessage` 和过时 Spring AI Options 代理。旧统计 JSON 和模型参数 `minClearCacheFrames` 只在读取时忽略，不再作为字段发布。当前清理周期选项最小值仍为 1。
- 首尾帧提示词必须使用 `videoPrompt`，不再将参考模式的 `prompt` 复制成视频词。导演规划异常直接标记任务失败，不继续普通提示词生成；原计划保留。
- 任务中心、渲染任务取消联动、角色配音级联删除、导演时长同步和制作画幅字典验证使用必需依赖，缺少模块时不再静默跳过。

历史快照转换、数据库升级器、旧密钥读取、历史枚举和已冻结渲染快照指纹适配属于数据读取边界，继续保留。正常的网络流式重试、原文标准化失败后使用原文，以及模型能力默认值仍是现行功能。
