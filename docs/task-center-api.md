# 任务中心 API

任务中心在查询层聚合 `ai_task` 与 `render_task`，不改变两张表各自的生命周期。
所有业务 ID 以字符串序列化给前端，避免雪花 ID 精度损失。

## 获取活跃任务

`GET /task-center/active`

返回 `PENDING`、`RUNNING`、`RETRYING`、`QUEUED`、`RENDERING` 任务摘要，包含：

- `sourceType`：`AI_TASK` / `RENDER_TASK`
- `taskId`、`category`、`taskType`、`status`
- `dramaId`、`episodeId`、`sceneId`、`shotId`
- `resumeAction`：前端恢复动作，例如 `SHOT_PROMPT_DERIVE`

## 获取最近任务

`GET /task-center/history?limit=50`

返回 AI 与渲染任务混合排序后的最近终态任务。`limit` 范围为 1～200。

## 获取任务摘要

`GET /task-center/{sourceType}/{taskId}`

`sourceType` 支持 `AI_TASK`、`RENDER_TASK`。列表接口不返回完整输入输出载荷，具体任务结果仍通过原任务详情接口读取。

## 取消任务

`POST /task-center/{sourceType}/{taskId}/cancel`

支持取消 `AI_TASK` 和 `RENDER_TASK`。AI 父任务取消时会停止派生子任务，并取消已关联的渲染任务。渲染与视频处理任务会调用网关 `POST /v1/tasks/cancel`，随后中断本地工作线程。

返回 `cancelled`、`status`、`upstreamStatus`、`message`。`upstreamStatus` 为 `CONFIRMED`、`UNCONFIRMED` 或 `FAILED`。`CONFIRMED` 表示上游确认取消，`UNCONFIRMED` 表示已中断本地请求但上游没有提供确认，`FAILED` 表示上游取消调用失败且本地工作已停止。此时 AI 任务状态为 `CANCEL_UNCONFIRMED`，渲染任务在 `cancelUpstreamStatus` 保留上游状态。

普通 OpenAI 兼容 Chat Completions 与 Ollama 同步请求没有通用的任务 ID 和取消端点。取消会中断本地请求线程；远端是否立即停止计算无法确认，因此界面显示“请求已中断（上游未确认）”。关闭弹窗或取消 WebSocket 订阅不会取消任务，需调用本接口。
