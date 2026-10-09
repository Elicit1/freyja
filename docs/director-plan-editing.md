# 可编辑的结构化导演决策计划

Ref2VA 和 FL2VA 共用 DirectorPlanEditor。默认 AUTO，未编辑按现有导演 AI → 提示词 AI 流程运行。只打开或取消编辑不会切换模式。保存或生成时提交修改后转为 MANUAL，后续生成直接将完整手工计划交给提示词 AI，直到明确恢复 AI 自动规划。

## API

`PUT /drama/shot/{id}/director-plan`

```json
{
  "directorPlanMode": "MANUAL",
  "directorPlanRevision": 0,
  "duration": 4,
  "directorPlan": {
    "schemaVersion": "1.0",
    "duration": 4,
    "shotSize": "MEDIUM_SHOT",
    "cameraAngle": "EYE_LEVEL",
    "cameraBeats": [{"startSec": 0, "endSec": 4, "movement": "STATIC", "speed": "NONE"}],
    "narrativeIntent": "创作者设定的意图"
  }
}
```

返回更新后的模式、完整计划及版本。恢复自动模式提交 AUTO 与当前版本，服务端清空手工计划并增加版本。过期版本返回业务码 409，须重新载入。编辑时可携带抽屉的时长及显式摄影锁定字段；保存 MANUAL 时与计划在同一 UPDATE 中保存这些物理上下文，并同步剧集时长统计，保证重新打开后仍有效。已有分镜的普通 PUT 不写计划，避免旧表单和采纳旧提示词覆盖新计划；新建分镜可携带 typed directorPlan。

`derive-prompt-task`、`derive-prompt-stream`、`derive-prompt-package` 支持 directorPlanMode、directorPlan、directorPlanRevision。请求中完整手工计划优先，未携带内容时读取已保存手工计划。任务创建前严格校验、保存并深拷贝，后台不会再读取其他用户编辑的计划。MANUAL 无效不会退回导演 AI。

严格校验包括协议、枚举、分镜时长、时间轴从 0 连续覆盖时长、不重叠、正节拍长度、数量上限、STATIC 无方向且速度 NONE，以及显式景别/运镜锁定冲突。不会自动排序、修改时间或截断节拍。

自动任务完成只在模式仍为 AUTO 且版本一致时按列更新导演计划；成功回填增加版本。手工任务完成只保存结果快照。实体的计划字段禁止普通 updateById 写入，避免其他异步流程覆盖。结果携带实际使用计划及模式/版本，界面修改后将旧结果标为失效，采纳前再次读取当前版本。

两种 H3 模式都注入完整 JSON 计划；即使系统自定义用户模板省略 DIRECTOR_PLAN 占位符，也会追加计划。上下文指纹含计划、模式、版本、时长和摄影锁定。外部 AI 导出包保留计划快照，解析时附 exportedContext 与导出包模式/版本/计划，不依赖重新读取计划重建导出内容。

## 数据库迁移

```powershell
python scripts/migrate_director_plan_editing.py
python scripts/migrate_director_plan_editing.py --apply
```

只添加 director_plan_mode（默认 AUTO）、director_plan_revision（默认 0），保留所有历史 director_plan_json。执行前在 logs/migrations 备份表结构与全部计划。可重复运行，不重跑包含 DROP TABLE 的初始化 SQL。Docker 及本地初始化 SQL 已同步字段。
