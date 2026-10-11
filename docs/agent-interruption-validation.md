# 任务残留与停止并发送验收

2026-10-11，分支 `codex/agent-foundation`。已更新 WSL Docker 的 backend 和 frontend，入口为 http://localhost/system/agent 。

任务中心原有 6 条 CHAPTER_DECOMPOSE 待处理记录，均没有开始时间。只读核对发现它们保留 Redis 原文请求，但没有 Planner、结果、MySQL 任务检查点、关联制作 Job 或活动 Agent Run。拆解原实现仅在创建事务提交后启动工作线程，回滚时没有结束已经存入 Redis 的待处理草稿。这 6 条记录现已通过任务中心取消接口结束归档，未删除记录、草稿或成果；归档后活跃列表为 0。

修复覆盖启动回滚后的失败收尾、Redis 最新状态覆盖过期 SQL 检查点后的筛选去重，以及取消提交事件确实启动子任务清理。清理保留已完成结果、限制在旧任务的后代范围，并同步已有 SQL 检查点终态，避免草稿过期后再次显示为进行中。已经开始的上游请求缺少停止确认时记录 CANCEL_UNCONFIRMED，待处理子任务可记录 CANCELLED。

聊天新增“停止并发送”，保留原来的“补充要求”和 Enter 行为。REPLACE_RUN 使用 expectedRunId，在同一事务内取消旧 Run 并接收新目标，保留会话和已保存的数据；重复请求幂等，过期标识拒绝执行，新配置无效时不取消旧 Run。旧取消响应不会清除新 Run 的活动状态。上一条消息仍在提交时输入的新草稿也会保留。

后端专项回归 110 项：106 通过、4 个 opt-in 数据库集成测试跳过。取消提交回调新增检查后，ProductionTaskRecoveryCancellationTest 的 2 项专项检查均通过。TypeScript、Vite、前后端 Docker 构建及部署通过。

独立 DeepSeek `deepseek-flash` 会话的真实 Chrome 页面检查 21 项通过，覆盖补充保持原任务、模型请求运行中停止并发送、旧任务立即取消、新目标完成、重复请求幂等、过期请求拒绝、历史保留、任务中心归档、配置保持及无浏览器异常。另有 4 项浏览器隔离检查验证提交期间继续编辑的新草稿不会丢失，补充与替换按钮仍可用。

保留的验收会话为 `2109284757243359234`；被替换任务 `2109286509711650817` 为 CANCELLED，新任务 `2109286516435120129` 为 COMPLETED，1 轮模型、0 次工具，回复“新的目标已接收：蓝色灯塔”。早期验收任务 `2109284766475022337` 已完成，其故事结果也保留。未调用业务写入工具，没有改动任何项目、资产、分镜或 Agent 配置。

记录与截图保留在忽略的 `logs/`：`agent-stalled-finished.json`、`agent-task-stalled-audit.sh`、`agent-replace-task-tests.log`、`agent-replace-child-callback-tests.log`、`agent-replace-live-fixture.json`、`agent-replace-live-checks.json`、`agent-replace-live-run.json`、`agent-replace-live-chat.png`、`agent-replace-draft-checks.json`。脚本使用独立验收会话，隔离其他会话的读取和已读变化，实际消息提交与模型执行使用本机真实服务。
