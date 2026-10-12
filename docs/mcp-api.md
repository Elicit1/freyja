# FREYJA 业务 MCP Server

FREYJA 作为 MCP Server，第三方 AI 平台作为 MCP Client。外部 Agent 负责推理、规划和生成结构化业务数据，FREYJA 负责授权、业务校验与持久化。人工工作台、内置 AI、第三方 MCP 三种入口并存，复用现有业务服务。

**内部所有 AI 能力不通过 MCP 开放。** 不提供 Agent 会话、模型/提供商查询或执行、Prompt、Skill、DirectorPlan、剧本拆解、生图、视频生成、语音生成、AI 任务与后处理模型调度。输入输出通过字段白名单过滤，嵌套引用也不携带内部 AI 字段。已有图片/音频/视频 URL 和历史视频版本选择属于业务资产操作。

使用 Spring AI 2.0.1 / MCP Java SDK 2.0.0 的无状态 Streamable HTTP，统一入口 `/mcp`。内部 ToolCallback 自动转换及 MCP 注解扫描均关闭，只注册显式业务工具。

## 开启服务

默认关闭，写入默认关闭。升级已有数据库时，先执行 [业务请求记录表 SQL](../src/main/resources/sql/mcp-business.sql)。Docker 新库初始化已包含此表；已有数据卷不会重跑初始化脚本，需手动升级。

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
| `FREYJA_MCP_ENABLED` | `false` | 开启 MCP，关闭时没有 MCP 路由 |
| `FREYJA_MCP_TOKEN` | 空 | 实际协议请求必须携带 `Authorization: Bearer <token>`，启用时不足 24 字符启动失败 |
| `FREYJA_MCP_ALLOWED_PROJECT_IDS` | 空 | 授权项目 ID，英文逗号分隔 |
| `FREYJA_MCP_ALLOW_ALL_PROJECTS` | `false` | 明确授权全部项目 |
| `FREYJA_MCP_WRITE_ENABLED` | `false` | 注册并允许业务写入工具 |
| `FREYJA_MCP_ALLOW_CREATE_PROJECTS` | `false` | 允许批次创建新项目，仍需写入开关 |
| `FREYJA_MCP_ALLOWED_ORIGINS` | 空 | 浏览器 Origin 白名单，英文逗号分隔 |

当前为单个预配置 Bearer 令牌，所有使用该令牌的客户端共享项目范围、权限和请求记录；没有多用户身份或 OAuth 自动登录。未授权项目时只可读取公共资产。公共库（包括历史 `dramaId=0` 数据）只读，写入资产必须归属项目。具有新建项目权限的调用者可访问 MCP 成功创建的项目；关闭该权限后，应将需要继续访问的项目加入明确授权列表。

带 `Origin` 的请求默认拒绝；已允许的浏览器可进行 POST 预检，实际请求仍需 Bearer 认证。原生客户端无需 Origin。远程使用 HTTPS，并保留认证头；访问令牌不能放入 URL。

Compose 已映射环境变量。后端入口为 `http://localhost:8080/mcp`；前端 Nginx `/api/` 代理入口为 `http://localhost/api/mcp`。

## 客户端连接

客户端须支持 Streamable HTTP 和认证头。Codex 配置示例（同名环境变量须在客户端进程中可用）：

```toml
[mcp_servers.freyja]
url = "http://localhost:8080/mcp"
bearer_token_env_var = "FREYJA_MCP_TOKEN"
tool_timeout_sec = 60
```

客户端与后端不在同一机器时，更换 `localhost`。其他 MCP 客户端填写相同 URL 与认证头。可使用 MCP Inspector 的 Streamable HTTP 模式验证初始化、发现和调用。

## 工具目录

只读模式 11 个工具，开启写入后共 19 个工具。资源 `freyja://guide` 提供业务使用规则。没有 MCP Prompts 能力。

| 只读工具 | 参数与用途 |
|---|---|
| `search_projects` | `keyword?`、`page?`、`size?`，仅查询授权项目 |
| `get_project` | `dramaId`，读取项目业务设定 |
| `get_project_outline` | `dramaId`、`level=EPISODE/SCENE/SHOT_GROUP`、`episodeId?`、`sceneId?`、`page?`、`size?` |
| `list_shots` | `dramaId`、`episodeId?`、`sceneId?`、`page?`、`size?`，分页有效分镜 |
| `get_shot` | `dramaId`、`shotId`，业务分镜与 `editFingerprint` |
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
| `batch_update_records` | 最多 100 项局部更新，每项需当前 `expectedFingerprint` |
| `get_business_operation` | 只读查询 MCP 业务请求已提交结果，用于断线恢复，不查询内部 AI 任务 |
| `clone_shot` | `requestId,dramaId,shotId,expectedFingerprint`，复制分镜并清理原分镜所属媒体版本/帧来源 |
| `set_default_character_look` | `requestId,dramaId,lookId,expectedFingerprint`，复用默认造型互斥规则 |
| `reorder_group_shots` | `requestId,dramaId,groupId,shotIds`，需组内完整且无重复的分镜列表 |
| `select_shot_video_version` | `requestId,dramaId,shotId,takeId,expectedFingerprint`，选择已有视频版本，复用尾帧失效规则 |

业务 ID 全部使用十进制 JSON **字符串**，包括嵌套人物、造型、道具 ID。数字形式、零、空字符串或超出 Long 范围均拒绝；JSON-RPC 请求 ID 保持原始类型。

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

业务字段以 `tools/list` 返回的 JSON Schema 为准。允许剧本、对白、描述、景别、运镜、资产关联等业务字段，拒绝内部 AI 字段。项目 ID 由顶层统一设置，不能在 `data` 中覆盖；分镜所属剧集由场次校验确定。

任一写入失败整批回滚，错误含 `BATCH_ROLLED_BACK: items[index]`。成功后业务数据与请求结果一起提交。100 项以上应拆批，各批使用不同 `requestId`；跨批不承诺原子性。追加导入不会替换已有记录。

## 批量修改与重试

先读记录取得 `editFingerprint`，再提交补丁。省略字段保留原值；空数组清空人物/道具关联。分镜 `resSceneId` / `resKeyframeId` 可用显式 `null` 清空，两者互斥；其他可空规则以 Schema 为准。不允许普通内容更新移动场次/镜头组/分镜所属层级或造型所属人物。

```json
{
  "requestId":"edit-20261013-001",
  "dramaId":"2032095628944588801",
  "items":[
    {"type":"SHOT","recordId":"2032095628944588802","expectedFingerprint":"<读取返回的64位十六进制指纹>","data":{"dialogue":"终于到了。"}}
  ]
}
```

指纹冲突返回 409 `EDIT_CONFLICT`。创作者锁定的景别/运镜不能通过 MCP 修改或解锁，须先在工作台解除锁定。业务修改保留内部 AI 数据；保留的人物/道具引用也保留其内部字段。

`requestId` 为 8–80 位字母、数字、下划线或连字符，建议 UUID。同一工具、相同参数和 ID 重试返回原成功结果，不重复写入；JSON 对象字段顺序不影响判定。同一 ID 用于不同内容或工具返回 409 `REQUEST_ID_REUSED`。并发相同请求通过唯一键和行锁串行处理。失败事务不留下已提交请求记录。

断线后查询 `get_business_operation`，或重发原参数。查询只读取已提交结果，不存在/尚未提交返回 404。请求记录不会进入内部 Agent 运行时。

## 返回与验证

同时返回文本和 `structuredContent`：

```json
{"ok":true,"data":{"id":"2032095628944588801"}}
```

业务错误返回 `isError=true`：

```json
{"ok":false,"error":{"code":403,"message":"PROJECT_ACCESS_DENIED: 项目不在 MCP 授权范围"}}
```

认证失败 HTTP 401，Origin 不允许 HTTP 403；请求体超过 2 MiB 返回 HTTP 413（包含分块传输）。拒绝跨项目引用、人物造型不匹配、公共资产写入、未知字段及非法参数。不开放删除、系统配置修改、任意 SQL 或任意 HTTP 转发。

SDK 在进入业务处理前也会校验公开 Schema，此类参数错误可能只有 `isError` 和文本内容；客户端应先检查 `isError`，再解析可用的结构化业务错误。

```powershell
$env:JAVA_HOME = 'F:\program_file\jdk26'
.\mvnw.cmd test '-Dtest=Mcp*Test'
```

协议测试使用真实 HTTP；数据库测试使用 H2 MySQL 兼容模式、实际 MyBatis 与原有 CRUD 服务，不写本地开发库。H2 验证不能替代目标 MySQL 上的上线验收。代码交付未自动升级数据库、启用服务或配置第三方客户端。

实现清单见 [mcp-implementation.md](mcp-implementation.md)。参考：[Spring AI MCP Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)、[无状态传输](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html)、[Codex MCP 配置](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)。
