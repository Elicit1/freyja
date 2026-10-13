# FREYJA 业务 MCP 实现清单

人工工作台、内置 AI、第三方 MCP 三种入口并存。外部 Agent 直接填写提示词工作台卡片与有序参考媒体；导演计划、人物/造型英文 Prompt 均可省略。内部 Agent、模型推理、AI 提示词生成、系统 Prompt 模板、Skill、生成与 AI 任务接口始终不开放。业务输入输出使用独立字段白名单；业务必填规则见 [字段核对](mcp-fields.md)。

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
- [x] 图片内容/尺寸和文件名校验、上传请求16 MiB专用上限；普通请求仍为2 MiB。
- [x] 上传摘要幂等、稳定存储路径和失败重试；图片不自动覆盖业务记录，不执行内部 AI。
- [x] 工作流权限、状态变更和字段过滤测试全部通过。

## 交付与验证

- [x] [使用说明](mcp-api.md)、配置和客户端示例。
- [x] Compose 环境变量及数据库升级 SQL。
- [x] `/system/mcp` 可视化配置页：整体/单工具热开关、权限和项目范围、令牌加密保存、状态和 Schema 展示。
- [x] “业务 CRUD”展示 10 类对象的 40 项单条 CRUD 与 10 项批量修改映射，直接操作各工具开关；总目录 68 个工具。
- [x] 旧通用修改开关迁移，保留已停用选择和新工具独立配置。
- [x] MCP 手写 SQL 统一放置于 `src/main/resources/mapper/mcp/*.xml`：业务分页和锁定、引用查询、幂等日志、配置读写；Java Mapper 仅保留声明，无 Java SQL 拼接或 XML 字符串插值。
- [x] 工具发现动态更新，缓存旧工具的调用同样拒绝；配置持久化及版本冲突保护。
- [x] Docker 新库初始化包含 MCP 业务记录表。
- [x] JDK 26 + Maven wrapper 完成专项及全量测试，记录现有失败项。

## 验证结果（2026-10-13）

- 最新 MCP 专项回归：77 项全部通过。覆盖 10 类对象单条 CRUD 与独立批量修改、HTTP Schema 隔离及幂等删除、父子删除事务回滚、默认造型提升、分镜视频版本逻辑删除、项目删除后的重放、直接外键/弱绑定/音频引用保护、忙碌记录与损坏引用拒绝、CRUD 能力映射与删除热开关、旧更新开关迁移，以及 XML 迁移后的真实 SQL 分页/日志/配置。原图片上传、业务字段和 AI 边界测试继续通过；MinIO 使用 mock，没有调用内部 AI。
- 此前完整回归 `./mvnw test`：646 项，638 通过、4 跳过、3 失败、1 错误，失败项为下列原有 4 项。本次字段与图片上传调整执行 MCP 专项回归，未重跑完整测试。
- `front/ npm run build`：TypeScript 与 Vite 生产构建通过。
- CRUD 配置页的 TypeScript 和 Vite 构建通过；真实浏览器显示 10 行 CRUD、50 个独立开关、68 个工具及 300 秒连接配置，无运行异常。工具目录、CRUD 映射和删除开关另由真实 HTTP 测试验证。
- WSL Docker 前后端已更新，原有 MCP 权限/令牌/工具配置保留，8 个项目和153 个分镜保留，6 个容器运行。基础镜像源返回 HTML 导致常规构建失败，改用现有运行镜像和已验证的 JAR/前端产物更新，并保留回退镜像标签。
- 将 MCP starter 与测试 H2 依赖移除后，对原有测试类执行 Surefire 基线检查，以下 4 项仍失败；本次没有修改其内部 AI / 业务实现或测试。

| 原有失败项 | 原因 |
|---|---|
| `PlannerCharacterDisambiguationTest.skillDocumentsRequestedNameAliasPronounAndAmbiguityCases` | 测试要求的 Skill 正文资源未打包 |
| `PlannerCharacterDisambiguationTest.distributableSkillZipPassesTheExistingPackageValidator` | 测试要求的 Skill ZIP 资源未打包 |
| `ResCharacterLookServiceTest.testUpdate_mismatchedCharacterId_throws400` | 断言与当前业务校验顺序不一致 |
| `ResCharacterLookServiceTest.testCreate_firstLook_autoSetDefault` | 测试数据缺少必填 `designDesc` |

最新上传验证日志：`target/mcp-image-upload-test.log`、`target/mcp-image-upload-front-build.log`。字段验证日志：`target/mcp-fields-test.log`、`target/mcp-fields-front-build.log`。此前验证日志：`target/mcp-config-test.log`、`target/mcp-runtime-test-result.log`、`target/mcp-full-test.log`、`target/mcp-front-build.log`、`target/mcp-baseline-test.log`。数据库测试使用 H2 MySQL 兼容模式；没有自动升级或修改运行中的 MySQL。

清单记录代码交付，不表示运行中的服务已开启 MCP。现有数据库升级、真实 MySQL 验收和第三方客户端配置按部署环境执行。
