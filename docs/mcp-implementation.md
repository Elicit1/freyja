# FREYJA 业务 MCP 实现清单

人工工作台、内置 AI、第三方 MCP 三种入口并存。外部 Agent 直接填写提示词工作台卡片与有序参考媒体；导演计划、人物/造型英文 Prompt 均可省略。已启用发布版本的 Skill 知识按需只读开放；内部 Agent、模型推理、AI 提示词生成、系统 Prompt 模板、生成与 AI 任务接口保持私有。业务输入输出使用独立字段白名单；业务必填规则见 [字段核对](mcp-fields.md)。

## 阶段 1：协议与读取

- [x] Spring AI WebMVC 无状态 Streamable HTTP `/mcp`。
- [x] 默认关闭；Bearer、Origin 白名单与项目范围。
- [x] 项目、大纲、分镜、资产、造型和业务字典读取。
- [x] 业务 ID 字符串化；协议 ID 保持原始类型。
- [x] 真实 HTTP 初始化、发现/调用、资源与鉴权测试。
- [x] 实际 SQL 分页、公共库和有效父级隔离测试。

## 阶段 2：批量业务写入

- [x] 独立写权限及项目创建权限。
- [x] 最多 100 项的批量读取、创建和删除；批量修改拆分为 10 个对象专用工具。
- [x] 10 类对象各有独立单条读取、创建、修改、删除工具；更新直接绑定对应 Service，无通用枚举更新入口。
- [x] 删除校验权限、原始指纹、子记录、直接外键和参考媒体引用；先子后父、事务回滚、逻辑删除和媒体文件保留。
- [x] 项目删除后仍可重放删除请求并查询已提交结果。
- [x] 有序 `clientKey` 引用与 `idMap`。
- [x] `append_storyboard` 只追加业务数据。
- [x] 事务回滚、持久化幂等和并发相同请求保护。
- [x] 指纹冲突、创作者锁及内部字段保留。
- [x] 外部提示词、导演计划、镜内人物/道具描述、参考图音频和配置参数的批量读写；仅确定性校验和持久化。
- [x] 分离创建必填和更新补丁约束；支持最小字段创建、剧集空 data 默认值、无导演计划的卡片直接保存。
- [x] 参考媒体 URL 和来源外键条件校验、槽位 ID 补全/重复拒绝、有序弱绑定及非默认造型一致性保护。
- [x] 批量事务、幂等、范围和业务/AI 边界测试全部通过。

## 阶段 3：业务工作流

- [x] 分镜复制，清理原分镜所属媒体版本/帧来源。
- [x] 默认造型切换及组内分镜完整重排。
- [x] 已有视频资产历史查询和版本选择。
- [x] 已提交 MCP 业务请求结果查询。
- [x] `upload_asset_image`：授权项目的 PNG/JPEG/静态 WebP Base64 上传，复用 MinIO 归档，最多10 MiB，返回 URL 后关联人物/造型/场景/关键图/道具或分镜。
- [x] `batch_upload_asset_images`：每批 1–20 张、每张 10 MiB、整批 20 MiB；先全量校验，再上传；返回有序记录和 `clientKey → url` 映射，保留幂等与稳定路径。
- [x] 图片内容/尺寸和文件名校验、单张请求16 MiB/批量请求32 MiB专用上限；普通请求仍为2 MiB，覆盖分块传输和伪造工具名请求头。
- [x] 上传摘要幂等、稳定存储路径和失败重试；图片不自动覆盖业务记录，不执行内部 AI。
- [x] `list_skills/read_skill/read_skill_file`：启用目录、入口正文、固定版本参考文件读取；单次正文128 KiB上限、路径和版本校验、停用与热开关保护，不输出对象存储地址或执行脚本。
- [x] 参考图 Schema 展开为明确对象；支持 `CHARACTER_LOOK` 输入并统一保存为 `CHARACTER_REFERENCE`，补齐明确造型 ID。
- [x] 协议层统一简短结构化错误和失败日志，停止 SDK 对批量联合 Schema 的重复前置错误展开；业务入口仍完整校验。
- [x] 创作指南要求完整分镜规格和资产绑定，可选 AUTO；外部 Agent 先读 H3 Skill，再填写模式对应的三段式 `videoPrompt` 或六段式 `prompt` 并读回核对。
- [x] 内置 `character-sheet-design` 源码与 ZIP：按用户要求在同一张图排列三视图、独立头部区及细节区，明确真实透明 PNG/允许的白底与身份一致性；初始化说明、目录工具及人物/造型参考图字段引导按任务阶段读取 Skill。仍是第三方 Agent 的执行指引，非服务端强制合规校验。
- [x] 工作流权限、状态变更和字段过滤测试全部通过。

## 交付与验证

- [x] [使用说明](mcp-api.md)、配置和客户端示例。
- [x] Compose 环境变量及数据库升级 SQL。
- [x] `/system/mcp` 可视化配置页：整体/单工具热开关、权限和项目范围、令牌加密保存、状态和 Schema 展示。
- [x] “业务 CRUD”展示 10 类对象的 40 项单条 CRUD 与 10 项批量修改映射，直接操作各工具开关；总目录 72 个工具，含批量图片上传和3个只读知识工具。
- [x] 旧通用修改开关迁移，保留已停用选择和新工具独立配置。
- [x] MCP 手写 SQL 统一放置于 `src/main/resources/mapper/mcp/*.xml`：业务分页和锁定、引用查询、幂等日志、配置读写；Java Mapper 仅保留声明，无 Java SQL 拼接或 XML 字符串插值。
- [x] 工具发现动态更新，缓存旧工具的调用同样拒绝；配置持久化及版本冲突保护。
- [x] Docker 新库初始化包含 MCP 业务记录表。
- [x] JDK 26 + Maven wrapper 完成专项及全量测试，记录现有失败项。

## 验证结果（2026-10-13）

### 可空字段默认值审计与视频卡片约束（2026-10-13）

核对历史提交 `25a63f9`（时段/天气/场景 Prompt 非必填）、`5323964`（景别/运镜非必填）、`f4b951b`（资源场景空间类型可空），发现修改未完整覆盖入库链路和旧数据库：剧情场次仍补 `DAY`，剧本拆解创建资源仍补 `INDOOR/DAY`；运行库的剧情场次列仍为 `NOT NULL DEFAULT INDOOR/DAY`、资源时段仍为 `NOT NULL DEFAULT DAY`，旧分镜列仍默认 `MEDIUM_SHOT/STATIC`。统一移除这些环境/摄影兜底，新库初始化及可重复执行的 `upgrade_scene_optional_environment.sql` 同步约束，不重写历史数据。前端新增场次不预选日间，空时段预览显示“未指定”。

MCP 空间类型和时段 Schema 明确列出枚举并接收 `null`；省略更新保留原值，显式 `null` 通过 SQL 清空，场次创建显式 `null` 覆盖资产继承，省略仍允许继承明确资产值。草稿首尾帧模式/3秒时长属于现有兼容约定，保留并在说明中公开；完成卡片需显式选模式/估时长。两种模式的字段映射、写后读回核对人物/造型等引用、7–8秒舒适长镜头与超过8秒适度拆镜引导、`non_diegetic_music=none / no background music` 无背景音乐要求同步至初始化、指南、字段说明及文档。服务端不猜测语义、不强制按8秒拆镜、不改写提示词。

专项回归133项全部通过（MCP 全套、剧本拆解、时长、短剧服务和视听提示词）；覆盖环境留空/明确值/资产继承/显式清空/无关补丁保留、7.5秒参考模式与造型引用读回、HTTP 实际发布 Schema 和指引。前端 TypeScript/Vite 构建通过。日志为 `logs/mcp-scene-tests.log`、`logs/mcp-scene-front.log`。部署验收记录在 `logs/mcp-scene-release/`。

### 题材输入与 MCP 专属画幅策略

功能提交 `a4b35fd` 已部署至 WSL Docker。项目题材支持自由文本与字典建议，原建议代码继续兼容；画幅要求仅增加在 MCP 创建/显式修改入口，未修改前端画幅选择器、`9:16` 默认值或内部 AI 拆解的画幅行为。外部 Agent 从初始化说明、`freyja://guide` 及创建参数 Schema 得知：用户未提供画幅时先读取启用选项并询问；明确委托 AI 决定时优先启用的 `16:9`。单条和批量创建均拒绝遗漏或不受支持的画幅，修改其他字段时保留原画幅；是否询问用户由客户端 Agent 执行。

100项 MCP/短剧专项回归全部通过；补充后的11项真实 HTTP 测试全部通过，覆盖单条/批量创建画幅校验及停用旧画幅后的无关字段更新。前端 TypeScript/Vite 构建、JAR 打包及7项浏览器检查通过，浏览器创建请求全部模拟，没有写入真实业务数据。日志为 `logs/mcp-genre-final-tests.log`、`logs/mcp-genre-http-final.log`、`logs/mcp-genre-final-front.log`、`logs/mcp-genre-final-package.log`、`logs/mcp-genre-browser-final.log`。

部署仅重建 backend/frontend，MySQL、Redis、MinIO、网关容器 ID 保持一致。部署前后现有6个项目、165个分镜、23个人物、23个造型、18个场景、13个道具、16个关键图数量一致，MCP 配置摘要一致。经原鉴权完成72个工具发现、必填画幅/自由题材 Schema、初始化与指南、人物设定图及 H3 Skill 正文/参考文件读取，当前启用画幅为 `9:16/16:9/1:1/4:3`；运行 JAR 哈希、前端首页与本轮构建产物一致，Nginx 配置检查通过。

镜像为 `astrag1/freyja-backend:genre-a4b35fd`、`astrag1/freyja-frontend:genre-a4b35fd`，同时更新运行标签 `1.0.0`；回退标签为对应的 `rollback-before-genre-a4b35fd`。部署及验收记录保存在 `logs/mcp-genre-release/`，不含明文凭据。客户端重连或刷新 `tools/list` 后执行下一轮测试。

### 前一轮图片上传与 Skill 指引

本轮日志定位：运行中容器在 12:27:52 拒绝 `create_shot` 的 `url/type/assetId` 未知字段及缺失 `sourceType/imageUrl`；12:28:04 缺失 `sourceId`；补齐后 12:28:21 单条创建成功。12:29:16 十一镜批量请求因造型来源枚举失败，并展开了全部联合 Schema 的重复错误。由此修复参数可发现性和类型兼容，未把已有单条 CRUD 误判为不可用。

本轮已于2026-10-13部署至 WSL Docker，功能提交为 `b25692f`。沿用现有运行镜像的 JDK/FFmpeg/Nginx 环境，将验证过的 JAR、前端构建和技能包打入新镜像，仅重建 backend/frontend，保留原 Compose 覆盖配置及挂载。客户端需重新连接或刷新 `tools/list`，才能发现新增工具和 Schema。

部署只读验收通过：经 Nginx `/api/mcp` 使用原鉴权配置完成初始化、72个工具发现、创作指南读取、人物设定图 Skill 正文读取，以及 H3 Skill 和 `base-en.txt/ref-en.txt` 读取；参考图 Schema 已展开并支持 `CHARACTER_LOOK`。后端运行 JAR 的 SHA-256 与已打包产物一致，前端首页与构建产物一致，Nginx 配置校验通过。现有9个项目、165个分镜及全部人物/造型/场景/道具/关键图数量保持一致，MCP 配置摘要保持一致；人物 Skill 首次启动自动发布。未运行外部生图、视频生成或业务写入测试。

发布镜像：`astrag1/freyja-backend:mcp-b25692f`、`astrag1/freyja-frontend:mcp-b25692f`，同时更新运行用 `1.0.0` 标签；原镜像保留为对应的 `rollback-before-mcp-b25692f` 标签。部署日志与不含明文凭据的验收摘要位于 `logs/mcp-release-*.log`、`logs/mcp-release-b25692f/before.json`、`logs/mcp-release-b25692f/after.json`。

- 本轮 MCP 专项回归：91 项全部通过。新增单条/十一镜批量造型引用、可识别参考图 Schema、简短结构化错误、批量上传顺序/幂等/预校验/额度/存储失败重试、HTTP 大请求与分块上限、Skill 正文及参考文件的启用版本/路径/UTF-8大小/完整性校验、知识工具与批量上传独立热开关；没有调用内部 AI。
- 收尾检查补齐服务端可配置的300秒超时（避免 SDK 默认10秒截断批量上传），保持服务名称/版本/说明配置；受影响的24项真实HTTP协议、运行开关及关闭状态测试再次全部通过。
- 人物设定图规范补充：skill-creator 验证通过；实际分发 ZIP 经现有标准校验器及启动发布流程验证，源码与 ZIP 内容一致。相关21项 Skill/MCP 测试全部通过，日志 `logs/mcp-character-sheet-tests.log`。仅验证知识包、指引和接口，未调用外部模型生成图片，不能据此保证第三方 Agent 必然遵循规范。
- 前一轮 MCP 专项回归：77 项全部通过。覆盖 10 类对象单条 CRUD 与独立批量修改、HTTP Schema 隔离及幂等删除、父子删除事务回滚、默认造型提升、分镜视频版本逻辑删除、项目删除后的重放、直接外键/弱绑定/音频引用保护、忙碌记录与损坏引用拒绝、CRUD 能力映射与删除热开关、旧更新开关迁移，以及 XML 迁移后的真实 SQL 分页/日志/配置。原图片上传、业务字段和 AI 边界测试继续通过；MinIO 使用 mock，没有调用内部 AI。
- 此前完整回归 `./mvnw test`：646 项，638 通过、4 跳过、3 失败、1 错误，失败项为下列原有 4 项。本次字段与图片上传调整执行 MCP 专项回归，未重跑完整测试。
- `front/ npm run build`：TypeScript 与 Vite 生产构建通过。
- 上一轮 CRUD 配置页的 TypeScript 和 Vite 构建通过；当时真实浏览器显示 10 行 CRUD、50 个独立开关、68 个工具及 300 秒连接配置，无运行异常。本轮前端构建通过，72个工具目录和热开关由真实 HTTP 测试验证，未重新执行浏览器检查。
- 上一轮 WSL Docker 前后端已更新，原有 MCP 权限/令牌/工具配置保留，8 个项目和153 个分镜保留，6 个容器运行。基础镜像源返回 HTML 导致常规构建失败，改用现有运行镜像和已验证的 JAR/前端产物更新，并保留回退镜像标签。本轮部署结果见上方记录。
- 将 MCP starter 与测试 H2 依赖移除后，对原有测试类执行 Surefire 基线检查，以下 4 项仍失败；本次没有修改其内部 AI / 业务实现或测试。

| 原有失败项 | 原因 |
|---|---|
| `PlannerCharacterDisambiguationTest.skillDocumentsRequestedNameAliasPronounAndAmbiguityCases` | 测试要求的 Skill 正文资源未打包 |
| `PlannerCharacterDisambiguationTest.distributableSkillZipPassesTheExistingPackageValidator` | 测试要求的 Skill ZIP 资源未打包 |
| `ResCharacterLookServiceTest.testUpdate_mismatchedCharacterId_throws400` | 断言与当前业务校验顺序不一致 |
| `ResCharacterLookServiceTest.testCreate_firstLook_autoSetDefault` | 测试数据缺少必填 `designDesc` |

本轮验证日志：`logs/mcp-feedback-tests.log`、`logs/mcp-feedback-protocol-final.log`、`logs/mcp-feedback-front-build.log`。历史上传验证日志：`target/mcp-image-upload-test.log`、`target/mcp-image-upload-front-build.log`。字段验证日志：`target/mcp-fields-test.log`、`target/mcp-fields-front-build.log`。此前验证日志：`target/mcp-config-test.log`、`target/mcp-runtime-test-result.log`、`target/mcp-full-test.log`、`target/mcp-front-build.log`、`target/mcp-baseline-test.log`。数据库测试使用 H2 MySQL 兼容模式；没有自动升级或修改运行中的 MySQL。

清单记录代码交付，不表示运行中的服务已开启 MCP。现有数据库升级、真实 MySQL 验收和第三方客户端配置按部署环境执行。
