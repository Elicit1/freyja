# Freyja agent 流式回复验收

日期：2026-10-11。分支：`codex/agent-foundation`。已部署到现有 WSL Docker，访问 `http://localhost/system/agent`。

## 行为与兼容

- 配置页“模型 → 回复方式”可选“流式输出”或“完整回复”，保存后只影响新任务。旧配置/快照缺失 `model.outputMode` 时仍为 `COMPLETE`，新安装默认 `STREAM`。
- 完整回复保留原 `ChatModel.call`；流式使用 `ChatModel.stream`，公开文字即时预览，完整模型结果保存后才执行工具。摘要生成仍使用完整调用。
- WebSocket 继续复用 `/ws/task-center`，临时预览使用 `AGENT_STREAM`，与持久消息 `AGENT_EVENT.seq` 分离。预览采用独立尝试标识、独立递增序号和累计文字；重连在持久回放后补发当前快照。
- 最终消息使用 `modelStepId` 替换预览。补充要求、重试、取消、替换任务与租约失效会清理旧预览；未完成文字不进入历史。内部推理与工具参数不进入公开预览。
- 无新增运行时依赖、数据表或数据库迁移。临时预览仍适用于单后端实例，进程重启依靠原有检查点恢复任务。

接口说明见 [agent-api.md](agent-api.md)。

## 自动化验证

后端相关回归共 **63 个用例通过**，覆盖配置兼容和快照、模型调用、上下文、检查点、事件提交、工具提问、流式与重连：

```powershell
$env:JAVA_HOME='F:\program_file\jdk26'
$env:PATH=$env:JAVA_HOME+'\bin;'+$env:PATH
.\mvnw.cmd test '-Dtest=SpringAiAgentStreamingTest,AgentStreamPublisherTest,AgentRuntimeTest,AgentConfigServiceTest,TaskCenterAgentReplayTest,AgentStoreTest,AgentContextBuilderTest,AgentControllerTest,AgentEventPublisherTest,SpringAiAgentModelGatewayTest'
```

OpenAI 兼容与 Ollama 测试使用真实 Spring AI 2.0.1 HTTP 适配器连接本地夹具服务，验证首段公开文字先于响应完成、碎片化工具参数完整聚合、工具不会由适配器直接执行、用量和未知用量、DeepSeek 空推理历史的异步兼容及请求作用域隔离。超时/取消测试确认释放流式订阅；提前结束测试拒绝将半截响应当作完整结果保存。

前端 `npm run build` 的 TypeScript 检查和 Vite 构建通过。构建保留项目已有大包提示。浏览器检查页面展示、模式保存、预览更新、断线重连、刷新恢复和完成后的消息合并。

## DeepSeek 实际 API 与浏览器验收

使用现有 `deepseek-flash` 配置，全部输入为专用原创虚构故事。测试会话不绑定已有项目，结果保留。主验收 **45 项检查通过，浏览器错误为 0**。

| 场景 | 会话 ID | Run ID | 结果 |
| --- | --- | --- | --- |
| 完整回复，在运行中切换配置为流式 | `2109311073047384065` | `2109311074599276546` | COMPLETED；原任务沿用旧快照，预览次数 0 |
| 流式文字、断开 WebSocket、刷新页面 | `2109311111769198593` | `2109311113123958786` | COMPLETED；观察到 22 次累计文字快照，重连和刷新恢复，最终正文仅一份 |
| 两个结构化问题、回答后继续小说 | `2109311209219657730` | `2109311210729607169` | COMPLETED；同一 Run，两轮模型，一次提问工具，回答后流式交付 |
| 流式中补充要求 | `2109311254446837762` | `2109311255726100481` | COMPLETED；两轮模型，旧推断未保存，按新要求以“晨光约定”开头 |
| 流式中取消 | 同上 | `2109311339402465282` | CANCELLED；预览清除，半截正文未保存 |
| 流式中停止并发送 | 同上 | `2109311424462950402` → `2109311509217251329` | 旧 Run CANCELLED，新 Run COMPLETED，交付“星星邮局”新故事 |

实际流式调用返回已知 Token 用量，所有 ID 在浏览器保持字符串；消息序号连续，临时片段不会改变回放游标。完成后最终正文与持久 ASSISTANT 消息一致，公开消息不含内部推理元数据。

最终配置为 `STREAM`、版本 15；原模型、形象、工具、指令、记忆与预算保留。结束时任务中心活跃任务数为 **0**。

本地证据和测试内容保存在 Git 忽略的目录中，不删除：

- `logs/agent-streaming-live-browser.cjs`：主浏览器/API 验收脚本。
- `logs/agent-streaming-live-fixture.json`：会话、任务和最终结果。
- `logs/agent-streaming-live-checks.json`、`logs/agent-streaming-live-frames.json`：检查结果与公开 WebSocket 帧。
- `logs/agent-streaming-preview.png`、`logs/agent-streaming-completed.png`：页面截图。
- `logs/agent-streaming-first-attempt-*`：首次浏览器脚本运行记录；对应会话及完整回复同样保留。

## 部署

本地 JDK 26 打包后端，Node 24 构建前端；使用已有缓存运行镜像构建，Dockerfile 和产物上下文位于 `logs/agent-streaming-deploy/`。执行 `docker compose up -d --no-deps backend frontend` 更新两项服务，数据库、Redis、MinIO 和网关保留。

旧镜像保留为 `astrag1/freyja-backend:pre-streaming-20261011` 与 `astrag1/freyja-frontend:pre-streaming-20261011`。未修改仓库部署配置或 Docker 镜像源。
