# AI 任务统一事件总线

前端只建立一条 `/ws/task-center` WebSocket。AI 请求使用 HTTP 创建持久化任务并立即取得字符串形式的任务 ID；AI 输出通过该 WebSocket 的 `AI_EVENT` 推送。视频渲染进度继续通过同一连接的 `RENDER_EVENT` 推送。HTTP 查询、提交和保存操作保持普通请求。

## 事件与恢复

客户端发送 `{"event":"AI_SUBSCRIBE","taskId":"<字符串 ID>","afterSeq":0}` 订阅任务。服务端先从 Redis 按序补发 `afterSeq` 之后的事件，再推送实时事件。客户端重连时发送每位监听者最后处理的序号；新增监听者可以从 0 回放。取消监听发送 `AI_UNSUBSCRIBE`。原分镜提示词的 `PROMPT_SUBSCRIBE`、`PROMPT_UNSUBSCRIBE` 入站协议继续兼容。

事件格式为 `{"event":"AI_EVENT","data":{"taskId":"...","seq":1,"type":"...","data":"..."}}`。事件类型包括 `task_created`、`stage`、`chunk`、`result`、`error`、`done`，剧本拆解另有 `segments_init`、`assets_discovered`、`channel_chunk`、`worker_status`。同一任务的事件在服务端串行写入 Redis 并广播；前端按监听者独立游标和序号交付，避免 Planner 与并发 Worker 片元乱序或重复。

事件日志保留 24 小时，用于短期重连和回放。任务的最终输出另存于 `ai_task.output_payload`，可通过 `GET /ai/tasks/{taskId}` 查询；任务中心可查看实时事件和持久化结果。关闭页面或中断监听不会停止后台任务。

## 接入范围

- 分镜提示词、整章与单集剧本拆解、分段 Worker 重试。
- 角色身份、造型、场景、道具提示词。
- 分镜视觉规划、角色声音设计与试听、分镜及整集配音。
- 分镜关键帧与资产生图。原有视频渲染、视频后处理继续通过任务中心连接广播任务状态。

资产提示词目前沿用同步模型调用，因而总线先推送阶段，再在模型返回后推送完整结果 JSON；整章拆解保留 Planner/Worker 的逐通道片元。旧 SSE 服务端路由保留给已有客户端，前端不再打开 SSE。

任务事件的本地广播适用于单个后端实例。若部署多个后端实例，需要将 Redis 日志后的实时广播改为 Redis Pub/Sub 或 Streams，以跨实例投递到持有客户端 WebSocket 的节点。
