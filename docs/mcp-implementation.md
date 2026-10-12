# FREYJA 业务 MCP 实现清单

人工工作台、内置 AI、第三方 MCP 三种入口并存。内部 Agent、模型、Prompt、Skill、DirectorPlan、生成和 AI 任务系统完整保留，一律不通过 MCP 暴露。业务输入输出使用独立字段白名单。

## 阶段 1：协议与读取

- [x] Spring AI WebMVC 无状态 Streamable HTTP `/mcp`。
- [x] 默认关闭；Bearer、Origin 白名单与项目范围。
- [x] 项目、大纲、分镜、资产、造型和业务字典读取。
- [x] 业务 ID 字符串化；协议 ID 保持原始类型。
- [x] 真实 HTTP 初始化、发现/调用、资源与鉴权测试。
- [x] 实际 SQL 分页、公共库和有效父级隔离测试。

## 阶段 2：批量业务写入

- [x] 独立写权限及项目创建权限。
- [x] 最多 100 项的批量读取、创建和修改。
- [x] 有序 `clientKey` 引用与 `idMap`。
- [x] `append_storyboard` 只追加业务数据。
- [x] 事务回滚、持久化幂等和并发相同请求保护。
- [x] 指纹冲突、创作者锁及内部字段保留。
- [x] 批量事务、幂等、范围和业务/AI 边界测试全部通过。

## 阶段 3：业务工作流

- [x] 分镜复制，清理原分镜所属媒体版本/帧来源。
- [x] 默认造型切换及组内分镜完整重排。
- [x] 已有视频资产历史查询和版本选择。
- [x] 已提交 MCP 业务请求结果查询。
- [x] 工作流权限、状态变更和字段过滤测试全部通过。

## 交付与验证

- [x] [使用说明](mcp-api.md)、配置和客户端示例。
- [x] Compose 环境变量及数据库升级 SQL。
- [x] Docker 新库初始化包含 MCP 业务记录表。
- [x] JDK 26 + Maven wrapper 完成专项及全量测试，记录现有失败项。

## 验证结果（2026-10-13）

- `./mvnw test -Dtest=Mcp*Test`：41 项全部通过，包含真实 HTTP 批量导入/重放、实际业务 CRUD、事务回滚、并发幂等、权限与字段白名单。
- `./mvnw test`：630 项，622 通过、4 跳过、3 失败、1 错误。MCP 测试全部通过。
- 将 MCP starter 与测试 H2 依赖移除后，对原有测试类执行 Surefire 基线检查，以下 4 项仍失败；本次没有修改其内部 AI / 业务实现或测试。

| 原有失败项 | 原因 |
|---|---|
| `PlannerCharacterDisambiguationTest.skillDocumentsRequestedNameAliasPronounAndAmbiguityCases` | 测试要求的 Skill 正文资源未打包 |
| `PlannerCharacterDisambiguationTest.distributableSkillZipPassesTheExistingPackageValidator` | 测试要求的 Skill ZIP 资源未打包 |
| `ResCharacterLookServiceTest.testUpdate_mismatchedCharacterId_throws400` | 断言与当前业务校验顺序不一致 |
| `ResCharacterLookServiceTest.testCreate_firstLook_autoSetDefault` | 测试数据缺少必填 `designDesc` |

本地验证日志：`target/mcp-business-test.log`、`target/mcp-full-test.log`、`target/mcp-baseline-test.log`。数据库测试使用 H2 MySQL 兼容模式；没有自动升级或修改运行中的 MySQL。

清单记录代码交付，不表示运行中的服务已开启 MCP。现有数据库升级、真实 MySQL 验收和第三方客户端配置按部署环境执行。
