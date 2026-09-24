# Freyja AI Skills 当前架构

## 目标

Skill 按 [Agent Skills 标准](https://agentskills.io/specification)作为目录包导入，不再使用 Freyja 私有的 `skill.yaml`、旧的单文件 Markdown 上传协议或其他自定义清单。一个标准包必须在根目录提供 `SKILL.md`，并在 YAML frontmatter 中声明 `name` 和 `description`；可选的 `license`、`compatibility`、`metadata`、`allowed-tools` 以及 `scripts/`、`references/`、`assets/` 等资源会随版本保存。

Skill 是知识资产，不是可执行插件。系统只保存和按需读取包内文件，不会因为导入 `scripts/` 就在服务端自动执行脚本。

## 导入边界

管理端通过 ZIP 上传标准 Skill 目录包。ZIP 可以直接将 Skill 文件放在根目录，也可以包含一个与 frontmatter `name` 相同的顶层目录；校验器会规范化为 Skill 根目录后检查：

- 必须存在根目录 `SKILL.md`；
- `SKILL.md` 必须是合法 UTF-8，并以 YAML frontmatter 开始；
- `name` 必须符合标准的小写 slug 约束，`description` 必须存在；
- ZIP 路径必须是安全相对路径，限制压缩大小、文件数量、单文件大小和解压后总大小；
- 不兼容旧的自由格式 `.md` 上传；入口文件只能作为标准 ZIP 中的 `SKILL.md` 导入。

名称、描述等标准字段以包内 `SKILL.md` 为唯一来源。管理端的展示名称和排序只属于系统 UI 元数据，不会覆盖标准内容。

## 数据模型

`ai_skill` 保存标准 `name`、展示信息、启用开关、排序和 `current_version_id`；`ai_skill_version` 保存不可变的 `revision_no`、标准 `SKILL.md` 原文、整个 Skill 包 SHA-256 和解压后总大小。`ai_skill_file` 保存每个包内文件的安全相对路径、文件类型、哈希、大小和 MinIO 对象键。入口正文放在数据库，包内附属文件按版本保存于 MinIO。

版本号从 1 开始，`(skill_id, revision_no)` 唯一。上传事务锁定 Skill 行，确保并发上传仍然得到单调递增版本；数据库事务回滚时会主动清理已经写入 MinIO 的对象。

## 管理 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/system/skills` | 查询 Skill 列表 |
| POST | `/system/skills/upload` | 上传标准 Skill ZIP；同名/指定 ID 创建新版本并立即生效 |
| GET | `/system/skills/{id}/versions` | 查看版本历史 |
| GET | `/system/skills/versions/{versionId}/preview` | 查看 `SKILL.md` 正文和包内文件索引 |
| GET | `/system/skills/versions/{versionId}/files` | 查看指定版本的文件索引 |
| POST | `/system/skills/{id}/switch-version` | 切换当前版本（回滚） |
| PUT | `/system/skills/{id}/enabled` | 启用或停用 |
| POST | `/system/skills/cache/refresh` | 手动清除 Skill 目录与正文缓存 |
| DELETE | `/system/skills/{id}` | 删除 Skill、历史版本和 MinIO 文件 |

## AI 加载链路

1. `SkillCatalogService` 缓存启用 Skill 目录，`SkillContentService` 按不可变版本 ID 缓存 `SKILL.md` 正文；同一进程内的提示词生成不会重复查询目录和正文表。
2. 导演规划请求使用缓存目录生成系统提示词，并写入本次 `LoadSkillToolSession` 的版本快照。
3. 模型调用 `load_skill(name)` 后，服务从快照指定的 `ai_skill_version.content` 读取标准 `SKILL.md`。单次请求最多加载 3 个不同 Skill，重复加载返回已加载确认。
4. 角色身份、人物造型、场景、道具、章节 Planner、普通分镜视觉方案等复制/手工通道，统一通过 `SkillPromptContextService` 注入用户明确选择的 Skill 正文；API 通道只注入启用 Skill 目录，并通过 `load_skill` Tool Calling 按需读取正文，同时输出 `log.info` 调用追踪。
5. Skill 停用、上传新版本、版本回滚、删除和手动刷新都会清除目录与正文缓存；缓存刷新后下一次请求重新建立当前版本快照。

当前 `load_skill` 的默认入口是数据库中的 `SKILL.md` 正文；标准包附属文件已经完成版本化索引和 MinIO 保存，后续可按工具权限扩展为显式的资源读取调用。

## H3 分镜提示词

MiniMax H3 的固定模板不再作为 Skill 或资源文件加载。`ShotAiVisualPlanService` 从 `SysConfig` 读取：

- `ai.prompt.minimax_h3_fl2va_system`
- `ai.prompt.minimax_h3_fl2va_user`
- `ai.prompt.minimax_h3_ref2va_system`
- `ai.prompt.minimax_h3_ref2va_user`

配置不存在时使用代码内默认模板；系统提示词和用户模板都会参与上下文指纹，配置变更后旧提示词会被识别为过期。

首尾帧 `FIRST_LAST_FRAME` 和参考图 `REFERENCE_MODE` 两种 H3 生成模式，在统一的 `buildPromptPackage` 入口读取 `ai.prompt.minimax_h3_*_system` 应用编排契约。API 自动生成会追加启用 Skill 目录，并要求模型在生成前主动通过 `load_skill(name="h3-prompt-writing")` 加载官方 H3 Prompt Skill；外部 AI 手工生成则展示同一条调用协议和冲突裁决规则，但不会伪造工具调用。H3 Skill 负责官方 Prompt 正文格式，`sys_config` 只负责 JSON 外壳、分镜事实、资产边界、`DIRECTOR_PLAN` 与台词等应用约束；两者冲突时不并列执行，按协议裁决。每个 Skill 的版本 ID、revision 和内容哈希都会纳入 `contextFingerprint`。

导演规划阶段仍保留 `load_skill(name)` 的按需 Tool Calling；该阶段用于让规划模型自行选择少量专业知识。H3 提示词生成阶段同样挂载同一版本快照的 `load_skill` Tool，但由 H3 系统协议要求模型主动加载 `h3-prompt-writing`，不在后端通过 `requiredSkillNames` 预加载，保留调用追踪并在未成功加载时记录告警。

## 前端

`/system/skills` 页面提供标准 ZIP 上传、更新、`SKILL.md` 正文预览、包内文件索引、版本历史/回滚、启停、删除和“刷新 Skill 缓存”按钮。页面不再提供旧的单文件 `.md` 上传入口。

## 数据库升级

新环境直接执行 `src/main/resources/sql/init.sql`。已有环境执行 `src/main/resources/sql/upgrade_ai_skill_standard_package.sql` 创建 `ai_skill_file`，再通过标准 Skill ZIP 重新导入需要纳入版本管理的 Skill。
