# FREYJA 业务 MCP Server

FREYJA 作为 MCP Server，第三方 AI 平台作为 MCP Client。外部 Agent 负责推理、规划和生成结构化业务数据，FREYJA 负责授权、业务校验与持久化。人工工作台、内置 AI、第三方 MCP 三种入口并存，复用现有业务服务。

**外部 Agent 可以直接填写提示词工作台卡片，关联有序参考图和音频，无需生成 DirectorPlan。** `prompt / firstFramePrompt / endFramePrompt / negativePrompt / videoPrompt / customScenePrompt`、人物动作/情绪/站位、道具提示词、参考图音频、生成模式与随机种子都属于可读写的业务内容。资产英文 Prompt 按需填写，已有图片可直接关联；导演计划仅作为可选的手工编辑内容保留。

各类型真实必填项、默认值、模式对应的卡片字段和媒体条件必填项见 [MCP 字段核对](mcp-fields.md)。创建和更新使用不同的约束：创建标明业务必填字段，更新仅提交需要修改的字段，不要求重传创建字段。

MCP 初始化指令与 `freyja://guide` 均引导外部 Agent 将非提示词生成文本写为简体中文，包括名称、标题、题材、简介、描述、人物小传、造型设计描述、剧本、动作、台词、旁白、声音描述及面向用户的说明。提示词按对应 Skill、模型或用户要求使用相应语言，其中的原语言台词保持原样；JSON 字段名、枚举代码、ID、URL、技术标识和已有创作者文本保持不变。Skill 中的英文非提示词示例不改变该语言约定。此规则属于生成指令引导，不代表服务端会自动翻译或校验文本语言。

**内部 AI 执行接口始终不开放。** MCP 不提供内部 Agent 会话、模型/提供商查询或调用、AI 提示词生成、导演 AI 规划、系统 Prompt 模板、剧本拆解、生图、视频生成、语音生成、AI 任务与后处理模型调度。已启用且已发布的 Skill 知识通过只读工具按需提供，供外部 Agent 自行生成内容；读取不执行 Skill 脚本或内部 AI。模型凭据、运行任务状态和内部 Skill 使用记录仍通过字段白名单排除。

使用 Spring AI 2.0.1 / MCP Java SDK 2.0.0 的无状态 Streamable HTTP，统一入口 `/mcp`。内部 ToolCallback 自动转换及 MCP 注解扫描均关闭，只注册显式业务工具。

## 开启服务

创建人物时默认使用创作者、原文或项目现有角色记录中的已确认姓名，所填写的 `name / canonicalName / displayName` 保持一致。先读取项目角色，优先复用已确认身份，避免以别名重复创建。已确认姓名保持原样，不因中文输出约定擅自翻译、音译、改名或替换为身份称谓、临时标签；仅在创作者明确要求时改名。未确认姓名时沿用原文明示称谓，不编造真实姓名，身份或命名有歧义时向创作者确认。

创建分镜时遵循 AI 剧本拆解的节奏原则：先根据剧情需要与具有独立叙事价值的转折确定拆镜，再按完整动作、对白及自然表演速度估算时长。同一目标的准备、执行、结果优先一镜完成。AUTO 倾向自然紧凑，普通完整动作通常参考 3–5 秒，短反应可更短，长对白或长动作按实际需要延长；其他节奏预设的区间也仅作参考。长镜头以 7–8 秒作为舒适渲染参考；预计超过 8 秒时主动在完整动作、对白轮次或独立叙事转折处适度拆镜，以减轻后台单次渲染压力。避免切碎同一连续动作，保留创作者明确要求的长镜头；不为固定时长档位或目标集时长拉长或填充内容；不增加停顿、慢动作或保险缓冲，并行动作共享时间，环境与持物状态不另计时。保留原文对白、速度及创作者要求，精确视频模型限制留到渲染阶段单独处理，服务端不按 8 秒自动截断或拆镜。

默认关闭，写入默认关闭。升级已有数据库时，先执行 [业务请求记录表 SQL](../src/main/resources/sql/mcp-business.sql)。Docker 新库初始化已包含此表；已有数据卷不会重跑初始化脚本，需手动升级。

场景环境可空：`sceneType=INDOOR/OUTDOOR/STUDIO/VIRTUAL`，`timeOfDay=DAY/NIGHT/SUNSET/DAWN`。按创作事实显式选择，河畔/街道使用 `OUTDOOR`；未指定不默认室内、室外或日间。剧情场次创建时省略可继承绑定资产，显式 `null` 留空；更新省略保留，显式 `null` 清空。旧库需执行 [场景环境可空升级 SQL](../src/main/resources/sql/upgrade_scene_optional_environment.sql)，仅改列约束/默认值，不改历史内容。

视频卡片显式选择模式：`REFERENCE_MODE` 使用 `prompt + refImages`（可选 `refAudios`），`FIRST_LAST_FRAME` 使用 `videoPrompt` 与首尾帧图片/提示词。写后使用 `get_scene/get_resource_scene/get_shot` 或 `batch_read_records` 核对场景类型/时段、`duration`、模式对应字段及人物/造型引用；以最新指纹修正偏差后再渲染。草稿省略模式/时长仍兼容首尾帧/3秒，完成卡片不能依赖这些默认值。

生成视频提示词时默认不加背景音乐/配乐：两种 H3 模式均保留 `non_diegetic_music`，按模型契约写 `N/A` 或 `none / no background music`；`overall_soundscape` 保留对白、环境声和必要音效，便于后期剪辑。用户明确要求背景音乐时按要求加入，内部制作规划使用 `includeBgm=true`。该默认规则优先于 Skill 的配乐示例。

进入工作台 **管理 → MCP 服务**（`/system/mcp`），即可配置令牌、项目范围、写入权限、整体开关和每个工具的开关；保存立即生效，无需重启。页面展示连接地址、工具说明、参数 Schema、开启状态和实际发布状态。

配置保存在 `sys_config` 的专用 `mcp.server.settings` 记录（固定 ID `900000000000000019`），令牌使用现有 `CryptoUtil` 加密，不回显明文。配置使用版本号避免覆盖其他窗口的修改。首次没有保存页面配置时采用环境变量默认值；保存后以页面持久化配置为准。原有内置 Agent 设置不受影响。

整体关闭时 `/mcp` 返回 404。工具关闭后立即从 `tools/list` 移除，缓存旧工具的调用也被服务端拒绝；关闭操作不会取消已经开始的业务事务。重新开启整体服务会保留各工具的选择。修改配置后客户端应重新发现工具。

```powershell
$env:FREYJA_MCP_ENABLED = 'true'
$env:FREYJA_MCP_TOKEN = '<至少24字符的随机访问令牌>'
$env:FREYJA_MCP_ALLOWED_PROJECT_IDS = '2032095628944588801'
# 按需开放业务写入：
$env:FREYJA_MCP_WRITE_ENABLED = 'true'
$env:JAVA_HOME = 'F:\program_file\jdk26'
.\mvnw.cmd spring-boot:run
```

真实令牌仅存放在本地环境或客户端配置，不提交仓库。

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `FREYJA_MCP_ENABLED` | `false` | 首次使用时的整体开关默认值；关闭时协议请求返回 404 |
| `FREYJA_MCP_TOKEN` | 空 | 令牌默认值；页面可加密保存替代令牌。实际请求必须携带 `Authorization: Bearer <token>` |
| `FREYJA_MCP_ALLOWED_PROJECT_IDS` | 空 | 授权项目 ID，英文逗号分隔 |
| `FREYJA_MCP_ALLOW_ALL_PROJECTS` | `false` | 明确授权全部项目 |
| `FREYJA_MCP_WRITE_ENABLED` | `false` | 注册并允许业务写入工具 |
| `FREYJA_MCP_ALLOW_CREATE_PROJECTS` | `false` | 允许批次创建新项目，仍需写入开关 |
| `FREYJA_MCP_ALLOWED_ORIGINS` | 空 | 浏览器 Origin 白名单，英文逗号分隔 |

当前为单个 Bearer 令牌，所有使用该令牌的客户端共享项目范围、权限和请求记录；没有多用户身份或 OAuth 自动登录。页面更换令牌后旧令牌立即失效。未授权项目时只可读取公共资产。公共库（包括历史 `dramaId=0` 数据）只读，写入资产必须归属项目。具有新建项目权限的调用者可访问 MCP 成功创建的项目；关闭该权限后，应将需要继续访问的项目加入明确授权列表。

带 `Origin` 的请求默认拒绝；已允许的浏览器可进行 POST 预检，实际请求仍需 Bearer 认证。原生客户端无需 Origin。远程使用 HTTPS，并保留认证头；访问令牌不能放入 URL。

Compose 已映射环境变量。后端入口为 `http://localhost:8080/mcp`；前端 Nginx `/api/` 代理入口为 `http://localhost/api/mcp`。

## 客户端连接

客户端须支持 Streamable HTTP 和认证头。Codex 配置示例（同名环境变量须在客户端进程中可用）：

```toml
[mcp_servers.freyja]
url = "http://localhost:8080/mcp"
bearer_token_env_var = "FREYJA_MCP_TOKEN"
tool_timeout_sec = 300
```

客户端与后端不在同一机器时，更换 `localhost`。服务端 `spring.ai.mcp.server.request-timeout` 配置为 300 秒；客户端工具调用等待时间也建议 300 秒，以容纳批量图片上传和业务写入，已连接客户端需同步修改。客户端超时不代表服务端事务已回滚，写入应使用原 `requestId` 重试或查询 `get_business_operation`。其他 MCP 客户端填写相同 URL 与认证头，并设置相应的工具调用超时。可使用 MCP Inspector 的 Streamable HTTP 模式验证初始化、发现和调用。

## 工具目录

提供 72 个可独立配置的工具，默认只读模式最多发布 22 个，开启写入权限后最多发布 72 个；实际发布数量还受整体开关与各工具开关控制。资源 `freyja://guide` 提供业务使用规则。没有 MCP Prompts 模板服务能力；业务记录中的提示词数据通过普通读写工具处理。

| 只读工具 | 参数与用途 |
|---|---|
| `search_projects` | `keyword?`、`page?`、`size?`，仅查询授权项目 |
| `get_project` | `dramaId`，读取项目业务设定 |
| `get_project_outline` | `dramaId`、`level=EPISODE/SCENE/SHOT_GROUP`、`episodeId?`、`sceneId?`、`page?`、`size?` |
| `list_shots` | `dramaId`、`episodeId?`、`sceneId?`、`page?`、`size?`，分页有效分镜 |
| `get_shot` | `dramaId`、`shotId`，完整分镜配置、提示词、导演计划、参考图音频与 `editFingerprint` |
| `search_assets` | `type=CHARACTER/CHARACTER_LOOK/SCENE/PROP/KEYFRAME`、`dramaId?`、`publicOnly?`、`characterId?`、`keyword?`、`page?`、`size?` |
| `list_skills` | `keyword? / page? / size?`，仅返回已启用且已发布 Skill 的轻量目录及字符串 `versionId` |
| `read_skill` | `name`、`versionId?`，返回 `SKILL.md` 和可读参考文件目录；指定版本已改变时返回 409 |
| `read_skill_file` | `name / versionId / path`，读取该版本 `references/` 下 `.md/.txt`；单文件最多 128 KiB，不返回对象键或存储 URL |
| `get_asset` | `type`、`assetId`、`dramaId?` |
| `list_character_looks` | `characterId`、`dramaId?`、`page?`、`size?` |
| `get_business_options` | `dictType`，业务字典白名单，不包含 AI 模型/生成选项 |
| `batch_read_records` | `dramaId?`、`items=[{type,recordId}]`，最多 100 项，各项独立结果/错误；非资产记录需项目范围 |
| `list_shot_video_versions` | `dramaId`、`shotId`、`page?`、`size?`，已有视频资产版本，过滤模型/Prompt/任务 |

分页默认 `page=1,size=20`，最多 50 条。公共资产搜索传 `publicOnly=true` 并省略 `dramaId`；项目资产搜索传 `dramaId`。造型搜索同时需要 `characterId`。

| 写入模式工具 | 用途 |
|---|---|
| `batch_create_records` | 单事务创建最多 100 项，支持有序 `clientKey` 引用 |
| `append_storyboard` | 向已有项目追加外部生成的结构化资产/分镜，不调用内部拆解 |
| `batch_delete_records` | 最多 100 项跨对象逻辑删除，每项需 `type,recordId,expectedFingerprint`；先子后父，不隐式级联 |
| `get_business_operation` | 只读查询 MCP 业务请求已提交结果，用于断线恢复，不查询内部 AI 任务 |
| `clone_shot` | `requestId,dramaId,shotId,expectedFingerprint`，复制分镜并清理原分镜所属媒体版本/帧来源 |
| `set_default_character_look` | `requestId,dramaId,lookId,expectedFingerprint`，复用默认造型互斥规则 |
| `reorder_group_shots` | `requestId,dramaId,groupId,shotIds`，需组内完整且无重复的分镜列表 |
| `select_shot_video_version` | `requestId,dramaId,shotId,takeId,expectedFingerprint`，选择已有视频版本，复用尾帧失效规则 |
| `prepare_asset_image_upload` | `requestId,dramaId,purpose,fileName,contentType,size,sha256`，申请 15 分钟有效的 MinIO 预签名 POST 表单；图片二进制由客户端直传，不进入模型输出 |
| `complete_asset_image_upload` | `requestId,dramaId,uploadId`，校验已直传文件并归档，返回正式 URL；确认的 requestId 与申请的 requestId 分开 |

业务 ID 全部使用十进制 JSON **字符串**，包括嵌套人物、造型、道具 ID。数字形式、零、空字符串或超出 Long 范围均拒绝；JSON-RPC 请求 ID 保持原始类型。

## 各业务对象独立 CRUD

| 业务对象 | 单条读取 | 单条创建 | 单条修改 | 单条删除 | 批量修改 |
|---|---|---|---|---|---|
| 项目 (PROJECT) | `get_project` | `create_project` | `update_project` | `delete_project` | `batch_update_projects` |
| 剧集 (EPISODE) | `get_episode` | `create_episode` | `update_episode` | `delete_episode` | `batch_update_episodes` |
| 剧情场次 (SCENE) | `get_scene` | `create_scene` | `update_scene` | `delete_scene` | `batch_update_scenes` |
| 镜头组 (SHOT_GROUP) | `get_shot_group` | `create_shot_group` | `update_shot_group` | `delete_shot_group` | `batch_update_shot_groups` |
| 分镜 (SHOT) | `get_shot` | `create_shot` | `update_shot` | `delete_shot` | `batch_update_shots` |
| 人物 (CHARACTER) | `get_character` | `create_character` | `update_character` | `delete_character` | `batch_update_characters` |
| 人物造型 (CHARACTER_LOOK) | `get_character_look` | `create_character_look` | `update_character_look` | `delete_character_look` | `batch_update_character_looks` |
| 场景资产 (RESOURCE_SCENE) | `get_resource_scene` | `create_resource_scene` | `update_resource_scene` | `delete_resource_scene` | `batch_update_resource_scenes` |
| 道具 (PROP) | `get_prop` | `create_prop` | `update_prop` | `delete_prop` | `batch_update_props` |
| 关键图 (KEYFRAME) | `get_keyframe` | `create_keyframe` | `update_keyframe` | `delete_keyframe` | `batch_update_keyframes` |

单条工具不接收 `type`。每个创建/修改 Schema 只包含该对象的白名单业务字段；内部更新直接绑定对应业务 Service，不再通过一个通用更新入口按枚举分派。

- 读取：`get_project` 沿用 `dramaId`，`get_shot` 沿用 `dramaId,shotId`；其余为 `recordId,dramaId`，公共资产读取可省略 `dramaId`。
- 创建：`requestId,dramaId,data`；`create_project` 必须省略 `dramaId`，需新建项目权限。
- 修改：`requestId,dramaId,recordId,expectedFingerprint,data`，`data` 是非空补丁。
- 删除：`requestId,dramaId,recordId,expectedFingerprint`。
- 批量修改：`requestId,dramaId,items=[{recordId,expectedFingerprint,data}]`，每批 1–100 条同类对象；没有 `type`，不能混入其他对象字段。

例如单条创建道具调用 `create_prop`：

```json
{"requestId":"create-prop-001","dramaId":"2032095628944588801","data":{"name":"旧手提箱","description":"棕色皮革"}}
```

成功返回 `requestId,dramaId,recordId,data`；删除返回 `requestId,dramaId,recordId,deleted:true`。批量修改成功返回 `requestId,dramaId,records`。所有写入复用同一幂等日志和权限校验。

旧 `batch_update_records` 已移除。客户端刷新 `tools/list` 后切换至对应工具；旧工具的开关配置自动迁移到所有独立修改/批量修改工具，已明确配置的新开关优先。旧成功请求仍可通过 `get_business_operation` 查询。

## 批量创建与追加

记录 `type` 支持 `PROJECT / EPISODE / SCENE / SHOT_GROUP / SHOT / CHARACTER / CHARACTER_LOOK / RESOURCE_SCENE / PROP / KEYFRAME`。`SCENE` 是剧情场次，`RESOURCE_SCENE` 是场景资产。

一批只操作一个项目。已有项目传顶层 `dramaId`；创建项目时省略它，首项必须为 `PROJECT`，并需新建权限。`clientKey` 批内唯一，ID 可用 `{"ref":"clientKey"}` 引用此前新建记录，不支持前向引用。返回 `idMap` 供后续批次使用。

下面参数可用于 `append_storyboard` 或 `batch_create_records`：

```json
{
  "requestId":"import-20261013-001",
  "dramaId":"2032095628944588801",
  "items":[
    {"clientKey":"ep1","type":"EPISODE","data":{"title":"第一集","episodeNo":1}},
    {"clientKey":"scene1","type":"SCENE","data":{"episodeId":{"ref":"ep1"},"name":"车站"}},
    {"clientKey":"group1","type":"SHOT_GROUP","data":{"sceneId":{"ref":"scene1"},"name":"到站"}},
    {"clientKey":"shot1","type":"SHOT","data":{"sceneId":{"ref":"scene1"},"shotGroupId":{"ref":"group1"},"scriptContent":"旅客走出车站。","duration":5}}
  ]
}
```

业务字段以 `tools/list` 返回的 JSON Schema 为准。项目创建需要 `title,aspectRatio`；画幅必须来自启用的系统选项。人物需要 `name/canonicalName/displayName` 至少一个；造型需要 `characterId,lookName,designDesc`，服装 Prompt 与妆发/状态 Prompt 均可省略。剧集可提交空 `data={}` 使用业务默认值；场次需要 `episodeId`，镜头组和分镜需要 `sceneId`。关键图需要 `name`，且图片 URL/描述/Prompt 至少一个。项目 ID 由顶层统一设置，不能在 `data` 中覆盖；分镜所属剧集由场次校验确定。模型、内部任务和 Skill 记录等字段仍被拒绝。

任一写入失败整批回滚，错误含 `BATCH_ROLLED_BACK: items[index]`。成功后业务数据与请求结果一起提交。100 项以上应拆批，各批使用不同 `requestId`；跨批不承诺原子性。追加导入不会替换已有记录。

## 批量修改与重试

先读记录取得 `editFingerprint`，再向对应对象的批量修改工具提交补丁，例如下面参数用于 `batch_update_shots`。省略字段保留原值；空数组清空人物/道具关联。分镜 `resSceneId` / `resKeyframeId` 可用显式 `null` 清空，两者互斥；其他可空规则以 Schema 为准。不允许普通内容更新移动场次/镜头组/分镜所属层级或造型所属人物。

```json
{
  "requestId":"edit-20261013-001",
  "dramaId":"2032095628944588801",
  "items":[
    {"recordId":"2032095628944588802","expectedFingerprint":"<读取返回的64位十六进制指纹>","data":{"scriptContent":"旅客停步，说：“终于到了。”"}}
  ]
}
```

指纹冲突返回 409 `EDIT_CONFLICT`。创作者锁定的景别/运镜和导演计划字段不能通过 MCP 修改或解锁，须先在工作台解除锁定。省略的提示词和人物/道具描述保留原值，显式 `null` 可以清空提示词；内部运行状态和媒体版本指针不能通过普通补丁覆盖。

`requestId` 为 8–80 位字母、数字、下划线或连字符，建议 UUID。同一工具、相同参数和 ID 重试返回原成功结果，不重复写入；JSON 对象字段顺序不影响判定。同一 ID 用于不同内容或工具返回 409 `REQUEST_ID_REUSED`。并发相同请求通过唯一键和行锁串行处理。失败事务不留下已提交请求记录。

断线后查询 `get_business_operation`，或重发原参数。查询只读取已提交结果，不存在/尚未提交返回 404。请求记录不会进入内部 Agent 运行时。

## 删除契约

单条使用各对象的 `delete_*` 工具，跨对象同事务使用 `batch_delete_records`：

```json
{
  "requestId":"delete-storyboard-001",
  "dramaId":"2032095628944588801",
  "items":[
    {"type":"SHOT","recordId":"2032095628944588802","expectedFingerprint":"<当前分镜指纹>"},
    {"type":"SHOT_GROUP","recordId":"2032095628944588804","expectedFingerprint":"<当前镜头组指纹>"}
  ]
}
```

删除是逻辑删除，复用业务 Service；不删除 MinIO 图片、音频和视频文件。删除分镜时，其视频抽卡记录随业务规则逻辑删除。项目、剧集、场次、镜头组和人物仍有子记录时返回 409 `HAS_CHILDREN`；分镜绑定关键图时也须先解除绑定或删除关键图。没有隐式大纲/资产级联删除。

资产仍被有效场次、分镜的直接外键、人物/道具关联、参考图来源、人物/造型弱绑定或音频人物绑定使用时返回 409 `RECORD_IN_USE`。作为其他分镜帧来源的分镜也不能直接删除。正在排队/渲染的分镜返回 `RECORD_BUSY`；损坏的引用 JSON 返回 `REFERENCE_DATA_INVALID`。

批量删除先校验全部原始指纹，再按提交顺序删除，须先子后父。任一失败全批回滚；删除默认造型引起的默认造型提升、删除分镜引起的剧集时长重算不会让同批后续项误报指纹冲突。项目删除成功后，在原项目授权仍有效时可用原请求重试或查询操作结果。

## 外部 Agent 完整分镜配置

使用 `create_shot / update_shot / batch_update_shots`，或者通过 `batch_create_records / append_storyboard` 创建完整大纲，不需要调用内部 AI 接口。`SHOT` 数据支持：

- 多模态参考模式：填写 `generationMode=REFERENCE_MODE`、工作台主卡片 `prompt` 和有序媒体列表。
- 首尾帧模式：填写 `generationMode=FIRST_LAST_FRAME`、视频卡片 `videoPrompt`；图片提示词 `firstFramePrompt / endFramePrompt` 和已有图片 `previewImageUrl / endFrameImageUrl` 按需提供。负向词与场景补充词按需填写。
- 人物引用：`characterId,lookId,designDesc,actionPrompt,emotionPrompt,positionTag`；道具引用：`propId,propName,propType,propPrompt`。
- `refImages` 最多 9 项，每项需要 `sourceType,imageUrl`；非 `UPLOAD` 来源还需要 `sourceId`，造型图 `CHARACTER_LOOK`（兼容 `CHARACTER_REFERENCE`）还需要 `characterId`。人物图 `sourceId` 是人物 ID，造型图则是造型 ID；服务端将造型来源统一保存为 `CHARACTER_REFERENCE` 并补齐 `lookId`。不能用 `url/type/assetId` 代替这些字段。`refAudios` 最多 3 项，每项需要 `sourceType,audioUrl`；`CHARACTER` / `TTS` 还需要 `characterId`，只引用已有声音，不执行 TTS。外键仍使用字符串或创建批次的 `clientKey` 引用，资产来源必须在授权范围，造型与人物必须匹配。
- 图像/音频数组顺序就是 `<Picture N> / <Audio N>` 顺序，不能提交空 URL。槽位 `id` 可省略，服务端生成；提交时须在数组中唯一。人物图通过 `characterId/lookId` 与主体弱绑定，`usageRole=NON_SUBJECT` 可关联画外人物，不强制加入出镜人物列表。用途由外部 Agent 明确指定，服务端不解析文字猜测绑定。重排时应同时更新最终 Prompt。
- 音频真实时长 `duration` 可选；提交时必须为 2–15 秒，已知时长总和不得超过 15 秒。`usageMode` 按需明确音色、对白、音乐或音效用途。
- `generationMode=FIRST_LAST_FRAME/REFERENCE_MODE`；`seed` 使用 1 到 9007199254740991 的整数字符串。已有首帧、尾帧和音频 URL 可以保存；视频版本仍使用专用选择工具。
- **常规外部 Agent 流程省略 `directorPlan / directorPlanMode`**，不会自动生成计划，更新时保留已有计划。需要手工编辑时可提交计划，自动保存为 `MANUAL` 并校验时长、节拍、枚举与创作者锁；不能伪造 `skills` 或内部版本/哈希。`directorPlanMode=AUTO` 仅重置，不启动 AI，重置时不能同时提交计划。

通过 `batch_update_shots` 直接填写参考模式卡片的补丁示例（人物 ID、媒体 URL 和指纹替换为真实读取结果，无需导演计划）：

```json
{
  "requestId":"external-shot-config-001",
  "dramaId":"2032095628944588801",
  "items":[{
    "recordId":"2032095628944588802", "expectedFingerprint":"<当前指纹>",
    "data":{
      "duration":5,
      "generationMode":"REFERENCE_MODE",
      "prompt":"<Picture 1> 约束主角身份和外貌。<Picture 2> 约束整体画风。主角走出车站，<Audio 1> 只参考他的音色。",
      "negativePrompt":"外部 Agent 生成的负向提示词",
      "characterRefs":[{"characterId":"2032095628944588803"}],
      "refImages":[
        {"sourceType":"CHARACTER","sourceId":"2032095628944588803","characterId":"2032095628944588803","imageUrl":"https://media.example/identity.png","usageRole":"SUBJECT","referenceRole":"IDENTITY"},
        {"sourceType":"UPLOAD","imageUrl":"https://media.example/style.png","usageRole":"STYLE"}
      ],
      "refAudios":[{"sourceType":"UPLOAD","characterId":"2032095628944588803","audioUrl":"https://media.example/voice.wav","duration":3,"usageMode":"VOICE_TIMBRE"}]
    }
  }]
}
```

上述示例只保存卡片与媒体关联，不会调用内部提示词生成、导演规划、生图或 TTS。图片通过 `prepare_asset_image_upload` 申请二进制直传、`complete_asset_image_upload` 确认后，再关联正式 URL；音频可关联已有 URL，现有业务 REST `POST /res/asset/upload` 也继续可用。该 REST 接口独立于 MCP Bearer 鉴权。单独保存外部 URL 不会自动下载或归档。

## Agent 图片上传

图片采用**预签名二进制直传**。客户端与 FREYJA 可以部署在不同机器，MCP 只传元数据和上传凭证；客户端使用已有 HTTP/文件能力发送本地图片，不需要安装独立上传脚本，也不需要转录 Base64。仅能调用 JSON 工具、没有文件发送能力的客户端不能自动完成二进制直传。

### 预签名二进制直传

1. 客户端计算真实文件大小和 SHA-256，通过 MCP 调用 `prepare_asset_image_upload`：

   ```json
   {
     "name": "prepare_asset_image_upload",
     "arguments": {
       "requestId": "external-look-prepare-001",
       "dramaId": "2032095628944588801",
       "purpose": "CHARACTER_LOOK",
       "fileName": "look.png",
       "contentType": "image/png",
       "size": 12345,
       "sha256": "<客户端计算的64位十六进制SHA-256>"
     }
   }
   ```

   `size` 是 JSON 整数（1–10485760 字节）；业务 ID 仍为字符串。`contentType` 仅支持 `image/png`、`image/jpeg`、`image/webp`。`purpose` 沿用下方用途映射。摘要不得由模型猜测。

2. 读取返回的 `uploadId、uploadUrl、method=POST、fields、fileField=file、expiresAt`。用 **multipart/form-data** 向 `uploadUrl` 发起普通 HTTP POST，将 `fields` 中的所有键和值作为原样表单字段，最后附加 `file` 文件部分，内容是本地文件原始字节。HTTP 库负责生成 multipart boundary，不要手动把整个请求的 Content-Type 设为 `image/png`；表单中的 `Content-Type` 字段使用返回值。不需要向 MinIO 发送 MCP Bearer 令牌，不修改签名字段，不把本地路径或 Base64 放进文件内容。

   表单策略绑定具体暂存对象、声明的 Content-Type 和精确文件大小，MinIO 在接收时强制校验。上传地址来自 `MINIO_EXTERNAL_ENDPOINT`，必须是客户端可访问的 **S3 API** 地址（通常端口 9000，不是控制台 9001）。预签名字段是短期上传凭证，不在日志或最终回复中展开。

3. 二进制上传成功后，通过 MCP 调用确认工具：

   ```json
   {
     "name": "complete_asset_image_upload",
     "arguments": {
       "requestId": "external-look-confirm-001",
       "dramaId": "2032095628944588801",
       "uploadId": "external-look-prepare-001"
     }
   }
   ```

   服务端检查项目归属、有效期、实际大小、SHA-256、真实图片格式和尺寸，再将已验证的字节写入正式媒体桶。返回 `requestId、uploadId、dramaId、purpose、url、contentType、size、width、height、sha256`。正式对象与暂存对象分开，即使签名仍未过期，重新直传也不会修改已经归档的图片。

4. 使用已有业务 CRUD，将**确认工具返回的正式 `url`** 填入对应字段，再读回核对。上传不会自动更新记录；暂存地址不能填写为 `referenceImageUrl` / `frameUrl`。

申请使用稳定的 `requestId`，相同内容重试复用同一个 `uploadId`、对象和到期时间，不会延长有效期；内容改变返回 409。凭证过期返回 410，使用新申请 requestId 重新上传。确认使用另一个稳定的 requestId；缺少文件返回 404，校验失败返回 400，存储失败返回 502，均可在有效期内纠正后重试。确认成功后，即使暂存文件已删除或凭证过期，相同确认 requestId 仍返回已提交结果。

暂存使用 `MINIO_MCP_UPLOAD_BUCKET`（默认 `freyja-mcp-uploads`），必须与正式媒体桶不同。应用首次申请时自动创建该独立桶并配置 `mcp/` 前缀一天过期，不赋予正式桶的匿名读取策略；完成确认并提交后尝试立即删除暂存对象，失败或遗弃上传由生命周期回收。该桶由此功能专用管理，MinIO 凭据需具备所需的桶初始化、生命周期配置及对象操作权限。若用浏览器直接上传，需为暂存桶配置允许实际前端 Origin 的 CORS。

两个新工具沿用 MCP Bearer、项目授权、全局写开关和独立工具开关。已有安装启动时会增量注册到能力发布目录，并移除旧的单张/批量 Base64 上传绑定，保留已有工具的自定义名称、开关、初始化说明和未发布草稿；客户端需刷新工具目录。无新增数据库表。

### 图片校验和用途

支持 PNG、JPEG 和静态 WebP，单张最多 **10 MiB**，最长边 16384 像素、总像素不超过 4000 万；按真实图片内容确定存储扩展名和 MIME 类型。WebP 校验 RIFF 容器、图像头和尺寸，PNG/JPEG 使用 ImageIO 解码校验。文件名不能包含路径。更新既有资产仍需最新的 `expectedFingerprint`。

| 上传用途 `purpose` | 业务记录和关联字段 |
|---|---|
| CHARACTER | 人物 `CHARACTER.referenceImageUrl` |
| CHARACTER_LOOK | 造型 `CHARACTER_LOOK.referenceImageUrl`，不要求补填英文 Prompt |
| SCENE | 场景资产 `RESOURCE_SCENE.referenceImageUrl`（按需也可填 `coverUrl`） |
| PROP | 道具 `PROP.coverUrl` |
| KEYFRAME | 关键图 `KEYFRAME.frameUrl` |
| SHOT_REFERENCE | 分镜 `SHOT.refImages` 中的 `imageUrl`，`sourceType=UPLOAD`；按需明确人物弱绑定及用途 |

图片校验通过后归档到正式媒体桶，使用外部访问地址生成 URL。申请与确认均要求 MCP 写入权限和已授权项目，可在配置页分别关闭；图片直传依靠 MinIO 预签名授权。历史已上传图片和业务请求结果继续保留。

普通 MCP JSON 请求体统一限制为 **2 MiB**，没有图片专用的大请求体例外。图片原始字节通过客户端到 MinIO 的独立 HTTP 请求传输。MinIO 和 SQL 不构成跨服务事务：归档成功而数据库提交失败时，使用原确认 requestId 重试会复用相同对象路径；确认结果提交前不会删除暂存来源。

多张图片逐张执行申请、直传、确认，分别保存每张文件与正式 URL 的对应关系，再使用业务批量接口关联资产。文件传输不通过批量 JSON 携带图片内容。

## 外部 Agent 的完整分镜与 H3 流程

项目题材 `genre` 支持最多64字符的自由文本；`drama_genre` 字典是固定建议，已有建议可以使用原字典值，未收录题材填写清晰名称即可，不编造枚举代码。剧集继承项目题材。其他枚举仍按相应字段的 Schema 和业务字典选择。

MCP 创建项目的 `aspectRatio` 必填。用户未指定时，外部 Agent 先读取 `drama_aspect_ratio` 的启用选项并通过其客户端对话请用户选择；明确交给 AI 决定时优先 `16:9`，若该值未启用则从其余启用选项决定。MCP 拒绝省略或不受支持的比例；编辑无关字段和追加剧集保持已有画幅。这条规则只针对 MCP 创作流程，前端画幅交互及内部 AI 拆解流程保持原行为。

### Skill 如何被发现

初始化响应的 server instructions、`freyja://guide`、`list_skills` 工具说明和相关字段说明都会引导外部 Agent 在创作开始及任务阶段改变时先读目录，按 `name/description` 选择 Skill，再读正文及所需参考文件。用户明确指定的布局、风格、背景和输出要求优先。目录只含已启用的发布版本；知识读取仍受 MCP 整体和单工具开关控制。

人物或造型设定图先读 `character-sheet-design`：用户要求三视图、细节、头身分区时，在同一张图内放全身正面/侧面/背面、独立头部特写区及已有设计细节；透明要求通过实际 Alpha 输出实现，白底仅在用户允许时使用。已有图片可直接关联，单张肖像等明确布局不会被强制改成三视图。三视图是同一人物的多个视角，后续视频不能因此把人物复制成多人。

内置来源为 `skills/character-sheet-design/SKILL.md`，可发布 ZIP 为 `skills/character-sheet-design.zip`。部署包含技能包的新版本后，现有启动初始化器会自动导入尚未发布的该 Skill；已有发布版本的同名 Skill 保持原版本及启停状态，不会因源文件变化自动更新。也可在 AI Skills 页面导入 ZIP。只修改本地源文件不会改变运行数据库中的已发布知识。

这些是明确的流程指引，不能证明第三方模型每次都已加载或遵守 Skill。保存草稿的 CRUD 仍允许不完整卡片，服务端不按关键词判断提示词是否合规。若必需 Skill 不可用，Agent 应说明缺失规范，不能声称已按该规范完成。用户要求可审阅结果时，Agent 应报告使用的 Skill 名称/版本及实际结果核对摘要。

### 填写与核对

1. 读取项目、大纲、相关资产和人物默认造型。填写基本规格、时长、镜头剧本、动作、对白/旁白/音效及适用的关联；无明确景别或运镜时可填 `AUTO` 并保持对应锁为 `false`。保留已有创作者锁，不凭空补剧情或资产 ID。
2. `characterRefs` 绑定真实 `characterId/lookId`，`propRefs` 绑定道具。主视觉 `resSceneId/resKeyframeId` 互斥；额外场景、道具和关键图可用 `refImages` 有序关联。`CHARACTER` 来源不能拿造型 ID 冒充人物 ID。
3. 先 `list_skills`，人物/造型设定图读取 `character-sheet-design`，分镜按目录读取 `cinematography / camera-direction` 等所需规范。写 MiniMax H3 视频卡片必须读取 `h3-prompt-writing`，再用返回的字符串 `versionId` 调用 `read_skill_file`。
4. 首尾帧模式读 `references/base-en.txt`，将 `integrated_multimodal_description / overall_soundscape / non_diegetic_music` 三段完整写入 `videoPrompt`；多模态参考模式读 `references/ref-en.txt`，将 `subject_definitions / summary / retention_analysis / detailed_description / overall_soundscape / non_diegetic_music` 六段完整写入 `prompt`。遵循实际 Skill 的时序、图号、声音和英文规则，台词保留原语言。
5. 保存后读回核对卡片、图号、资产/造型及生成模式。图片提示词已填写不能代表视频卡片完成。创建允许草稿；MCP 保存不会自动生成缺失卡片。Skill 停用返回 404，版本变化返回 409，重新读取目录和正文再继续，不能默默混用新旧规范。

每次正文读取最多 128 KiB；Skill 目录不含全文，参考文件按需读取。各工具可在 MCP 管理页独立关闭。附属脚本、资产二进制、任意路径和存储地址不开放。外部 Agent 写工作区素材包时使用正常文件编辑工具；Node 文件写入遇到 `EPERM` 应切换到具有工作区权限的文件工具。

## 配置页后台接口

这些是工作台管理 REST API，不发布为 MCP 工具。与现有系统管理接口采用同一管理边界。

| 接口 | 用途 |
|---|---|
| `GET /system/mcp` | 获取当前配置、令牌是否已配置、72 个工具的状态/Schema及 10 类对象的 `crudCapabilities`；不返回令牌 |
| `PUT /system/mcp` | 保存完整权限/来源配置及可选新令牌；必须提供当前 `revision` |
| `PUT /system/mcp/enabled` | `{revision,enabled}` 控制整体开关 |
| `PUT /system/mcp/tools/{name}` | `{revision,enabled}` 控制单个已知业务工具；未知名称拒绝 |

## 返回与验证

同时返回文本和 `structuredContent`：

```json
{"ok":true,"data":{"id":"2032095628944588801"}}
```

业务错误返回 `isError=true`：

```json
{"ok":false,"error":{"code":403,"message":"PROJECT_ACCESS_DENIED: 项目不在 MCP 授权范围"}}
```

认证失败 HTTP 401，Origin 不允许 HTTP 403；普通请求体超过 2 MiB、单张上传请求超过 16 MiB、批量上传请求超过 32 MiB 返回 HTTP 413（包含分块传输）。上传图片本身仍限制每张 10 MiB、整批 20 MiB。拒绝跨项目引用、人物造型不匹配、公共资产写入、未知字段及非法参数。不开放系统配置修改、任意 SQL 或任意 HTTP 转发。

所有显式工具在业务入口统一校验公开 Schema 和条件必填项，失败返回简短 `isError=true` 与结构化错误。SDK 的重复前置校验关闭，避免批量 `oneOf` 错误重复列出全部对象分支；校验仍在服务端执行。错误指出实际批次项和媒体字段，不回显请求载荷。失败工具、错误码和简短原因写入日志。

```powershell
$env:JAVA_HOME = 'F:\program_file\jdk26'
.\mvnw.cmd test '-Dtest=Mcp*Test'
```

协议测试使用真实 HTTP；数据库测试使用 H2 MySQL 兼容模式、实际 MyBatis 与原有 CRUD 服务，不写本地开发库。H2 验证不能替代目标 MySQL 上的上线验收。代码交付未自动升级数据库、启用服务或配置第三方客户端。

实现清单见 [mcp-implementation.md](mcp-implementation.md)。参考：[Spring AI MCP Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)、[无状态传输](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html)、[Codex MCP 配置](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)。


## 统一能力目录

工具名称、说明、Schema 和创作规则由 MySQL 发布快照驱动，在 `/system/ai-capabilities` 编辑；内部 Agent 复用同一处理器。Redis 负责缓存和刷新通知，故障时回源 MySQL。完整实现、管理 API 和升级说明见 [统一能力平台](mcp-mysql-redis-refactor-plan.md)。原 MCP 管理页继续管理认证、项目授权和工具开关。默认不生成背景音乐，用户明确要求配乐时允许；内部提示词使用显式 includeBgm 决策。升级后内部 Agent 需新建会话，不兼容旧运行快照。
