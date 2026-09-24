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

当前支持取消 `RENDER_TASK`。AI 提示词分析暂不强制中断，关闭弹窗后继续执行并可从任务中心恢复结果。
