# 分镜提示词任务事件流

前端通过 `POST /drama/shot/derive-prompt-task` 提交 `ShotPromptDeriveDTO`，响应 `R<String>` 中的字符串为持久化 `ai_task.id`。模型推理在后台继续运行；HTTP 响应、页面或 WebSocket 断开不会取消任务。

浏览器复用任务中心唯一的 `ws(s)://<host>/ws/task-center` 连接。订阅消息：

```json
{"event":"PROMPT_SUBSCRIBE","taskId":"2032095628944588801","afterSeq":0}
```

服务端先按 `afterSeq` 补发，再推送同一任务的新事件。消息格式：

```json
{"event":"PROMPT_EVENT","data":{"taskId":"2032095628944588801","seq":2,"type":"stage","data":"正在加载 Skill…"}}
```

事件类型：`task_created`、`stage`、`chunk`、`result`、`error`、`done`。前端记录最后处理的 `seq`，重连时再次订阅，按序号去重。取消订阅发送 `{"event":"PROMPT_UNSUBSCRIBE","taskId":"..."}`；这仅关闭页面订阅，不取消后台任务。

事件先写入 Redis 的任务专属列表，再向当前订阅者推送。列表在最后一条事件写入后保留 24 小时，之后自动过期；最终结果与状态仍保存在 `ai_task`，可通过 `GET /drama/shot/prompt-task/{taskId}` 恢复。Redis 缓存过期或暂时不可用时，前端使用任务详情恢复最终结果。原 `POST /drama/shot/derive-prompt-stream` SSE 接口保留兼容，当前分镜提示词弹窗使用新接口。

渲染进度同样通过任务中心连接以 `RENDER_EVENT` 推送；系统不再注册 `/ws/render-tasks` 端点。
