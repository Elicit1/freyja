# FREYJA 业务 MCP Server

FREYJA 作为 MCP Server，第三方 AI 平台作为 MCP Client。外部 Agent 负责推理、规划和生成结构化业务数据，FREYJA 负责授权、业务校验与持久化。人工工作台、内置 AI、第三方 MCP 三种入口并存，复用现有业务服务。

**外部 Agent 可以直接填写提示词工作台卡片，关联有序参考图和音频，无需生成 DirectorPlan。** `prompt / firstFramePrompt / endFramePrompt / negativePrompt / videoPrompt / customScenePrompt`、人物动作/情绪/站位、道具提示词、参考图音频、生成模式与随机种子都属于可读写的业务内容。资产英文 Prompt 按需填写，已有图片可直接关联；导演计划仅作为可选的手工编辑内容保留。

各类型真实必填项、默认值、模式对应的卡片字段和媒体条件必填项见 [MCP 字段核对](mcp-fields.md)。创建和更新使用不同的约束：创建标明业务必填字段，更新仅提交需要修改的字段，不要求重传创建字段。

**内部 AI 执行接口始终不开放。** MCP 不提供内部 Agent 会话、模型/提供商查询或调用、AI 提示词生成、导演 AI 规划、系统 Prompt 模板、Skill、剧本拆解、生图、视频生成、语音生成、AI 任务与后处理模型调度。保存外部生成的内容只校验和持久化，不调用这些服务。模型凭据、运行任务状态和内部 Skill 使用记录仍通过字段白名单排除。

使用 Spring AI 2.0.1 / MCP Java SDK 2.0.0 的无状态 Streamable HTTP，统一入口 `/mcp`。内部 ToolCallback 自动转换及 MCP 注解扫描均关闭，只注册显式业务工具。

## 开启服务

默认关闭，写入默认关闭。升级已有数据库时，先执行 [业务请求记录表 SQL](../src/main/resources/sql/mcp-business.sql)。Docker 新库初始化已包含此表；已有数据卷不会重跑初始化脚本，需手动升级。

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

客户端与后端不在同一机器时，更换 `localhost`。建议工具调用等待时间为 300 秒，以容纳大图上传和批量业务写入；此项是客户端配置，已连接的客户端需要同步修改。客户端超时不代表服务端事务已回滚，写入应使用原 `requestId` 重试或查询 `get_business_operation`。其他 MCP 客户端填写相同 URL 与认证头，并设置相应的工具调用超时。可使用 MCP Inspector 的 Streamable HTTP 模式验证初始化、发现和调用。

## 工具目录

提供 68 个可独立配置的业务工具，默认只读模式最多发布 19 个，开启写入权限后最多发布 68 个；实际发布数量还受整体开关与各工具开关控制。资源 `freyja://guide` 提供业务使用规则。没有 MCP Prompts 模板服务能力；业务记录中的提示词数据通过普通读写工具处理。

| 只读工具 | 参数与用途 |
|---|---|
| `search_projects` | `keyword?`、`page?`、`size?`，仅查询授权项目 |
| `get_project` | `dramaId`，读取项目业务设定 |
| `get_project_outline` | `dramaId`、`level=EPISODE/SCENE/SHOT_GROUP`、`episodeId?`、`sceneId?`、`page?`、`size?` |
| `list_shots` | `dramaId`、`episodeId?`、`sceneId?`、`page?`、`size?`，分页有效分镜 |
| `get_shot` | `dramaId`、`shotId`，完整分镜配置、提示词、导演计划、参考图音频与 `editFingerprint` |
| `search_assets` | `type=CHARACTER/CHARACTER_LOOK/SCENE/PROP/KEYFRAME`、`dramaId?`、`publicOnly?`、`characterId?`、`keyword?`、`page?`、`size?` |
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
| `upload_asset_image` | `requestId,dramaId,purpose,dataBase64,fileName?`，上传 PNG/JPEG/静态 WebP 参考图，返回可关联的 URL；单张最多 10 MiB |

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

业务字段以 `tools/list` 返回的 JSON Schema 为准。人物需要 `name/canonicalName/displayName` 至少一个；造型需要 `characterId,lookName,designDesc`，服装 Prompt 与妆发/状态 Prompt 均可省略。剧集可提交空 `data={}` 使用业务默认值；场次需要 `episodeId`，镜头组和分镜需要 `sceneId`。关键图需要 `name`，且图片 URL/描述/Prompt 至少一个。项目 ID 由顶层统一设置，不能在 `data` 中覆盖；分镜所属剧集由场次校验确定。模型、内部任务和 Skill 记录等字段仍被拒绝。

任一写入失败整批回滚，错误含 `BATCH_ROLLED_BACK: items[index]`。成功后业务数据与请求结果一起提交。100 项以上应拆批，各批使用不同 `requestId`；跨批不承诺原子性。追加导入不会替换已有记录。

## 批量修改与重试

先读记录取得 `editFingerprint`，再向对应对象的批量修改工具提交补丁，例如下面参数用于 `batch_update_shots`。省略字段保留原值；空数组清空人物/道具关联。分镜 `resSceneId` / `resKeyframeId` 可用显式 `null` 清空，两者互斥；其他可空规则以 Schema 为准。不允许普通内容更新移动场次/镜头组/分镜所属层级或造型所属人物。

```json
{
  "requestId":"edit-20261013-001",
  "dramaId":"2032095628944588801",
  "items":[
    {"recordId":"2032095628944588802","expectedFingerprint":"<读取返回的64位十六进制指纹>","data":{"dialogue":"终于到了。"}}
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
- `refImages` 最多 9 项，每项需要 `sourceType,imageUrl`；非 `UPLOAD` 来源还需要 `sourceId`，造型图 `CHARACTER_REFERENCE` 还需要 `characterId`。`refAudios` 最多 3 项，每项需要 `sourceType,audioUrl`；`TTS` 还需要 `characterId`，只引用已有声音，不执行 TTS。外键仍使用字符串或创建批次的 `clientKey` 引用，资产来源必须在授权范围，造型与人物必须匹配。
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

上述示例只保存卡片与媒体关联，不会调用内部提示词生成、导演规划、生图或 TTS。图片可先使用 `upload_asset_image` 上传，再关联返回 URL；音频可关联已有 URL，现有业务 REST `POST /res/asset/upload` 也继续可用。该 REST 接口独立于 MCP Bearer 鉴权。单独保存外部 URL 不会自动下载或归档。

## Agent 图片上传

通过标准 `tools/call` 调用 `upload_asset_image`，图片内容直接放入 `dataBase64`。支持 PNG、JPEG 和静态 WebP，单张最多 **10 MiB**，最长边 16384 像素、总像素不超过 4000 万；按图片真实内容确定存储扩展名和 MIME 类型，禁止 SVG、其他文件类型、URL、本地文件路径和 `data:` 前缀。WebP 校验 RIFF 容器、图像头和尺寸，PNG/JPEG 使用 ImageIO 解码校验。`fileName` 可省略，提交时不能带路径。

```json
{
  "name":"upload_asset_image",
  "arguments":{
    "requestId":"external-look-upload-001",
    "dramaId":"2032095628944588801",
    "purpose":"CHARACTER_LOOK",
    "fileName":"look.png",
    "dataBase64":"<图片文件的纯 Base64 内容>"
  }
}
```

返回 `requestId,dramaId,purpose,url,contentType,size,width,height,sha256`。上传不要求资产已创建，也不自动覆盖任何资产；随后使用对应资产的单条创建/修改或批量工具关联返回的 `url`，更新现有资产仍需要 `expectedFingerprint`。

| 上传用途 `purpose` | 业务记录和关联字段 |
|---|---|
| CHARACTER | 人物 `CHARACTER.referenceImageUrl` |
| CHARACTER_LOOK | 造型 `CHARACTER_LOOK.referenceImageUrl`，不要求补填英文 Prompt |
| SCENE | 场景资产 `RESOURCE_SCENE.referenceImageUrl`（按需也可填 `coverUrl`） |
| PROP | 道具 `PROP.coverUrl` |
| KEYFRAME | 关键图 `KEYFRAME.frameUrl` |
| SHOT_REFERENCE | 分镜 `SHOT.refImages` 中的 `imageUrl`，`sourceType=UPLOAD`；按需明确人物弱绑定及用途 |

文件归档到当前 MinIO 配置的桶，使用外部访问地址生成 URL。上传要求 MCP 写入权限和已授权项目，公共资产写入仍不允许；配置页中的“上传资产参考图”开关可单独关闭。全部通过 MCP 原有 Bearer/Origin 校验，不额外调用 REST 上传接口。

同一 `requestId`、项目、用途、文件内容和文件名重试返回已提交结果，不重复上传；文件内容以 SHA-256 参与幂等校验，Base64 表示形式不影响判断。数据库只保存摘要和返回结果，不保存 Base64。MinIO 和 SQL 不构成一个跨服务事务：归档成功而数据库提交失败时，原请求重试会覆盖相同对象路径；上传后未关联的图片保留为已上传文件。文件上传和后续资产关联是两个独立业务操作。

普通 MCP 请求体仍限制为 2 MiB；服务端只对实际 `tools/call` 的 `upload_asset_image` 请求放宽到 16 MiB，以容纳 Base64。客户端或反向代理若另有请求体上限，也需要容纳该请求。Agent 应通过客户端代码/文件工具读取并编码图片，避免在对话中手工拼接大量 Base64。

## 配置页后台接口

这些是工作台管理 REST API，不发布为 MCP 工具。与现有系统管理接口采用同一管理边界。

| 接口 | 用途 |
|---|---|
| `GET /system/mcp` | 获取当前配置、令牌是否已配置、68 个工具的状态/Schema及 10 类对象的 `crudCapabilities`；不返回令牌 |
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

认证失败 HTTP 401，Origin 不允许 HTTP 403；普通请求体超过 2 MiB、图片上传请求体超过 16 MiB 返回 HTTP 413（包含分块传输）。上传图片本身仍限制 10 MiB。拒绝跨项目引用、人物造型不匹配、公共资产写入、未知字段及非法参数。不开放系统配置修改、任意 SQL 或任意 HTTP 转发。

SDK 在进入业务处理前也会校验公开 Schema，此类参数错误可能只有 `isError` 和文本内容；客户端应先检查 `isError`，再解析可用的结构化业务错误。

```powershell
$env:JAVA_HOME = 'F:\program_file\jdk26'
.\mvnw.cmd test '-Dtest=Mcp*Test'
```

协议测试使用真实 HTTP；数据库测试使用 H2 MySQL 兼容模式、实际 MyBatis 与原有 CRUD 服务，不写本地开发库。H2 验证不能替代目标 MySQL 上的上线验收。代码交付未自动升级数据库、启用服务或配置第三方客户端。

实现清单见 [mcp-implementation.md](mcp-implementation.md)。参考：[Spring AI MCP Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)、[无状态传输](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html)、[Codex MCP 配置](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)。
