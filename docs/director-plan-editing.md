# 可编辑的结构化导演决策计划

## 镜头输入变更与本地生成

分镜表增加 `director_plan_input_hash CHAR(64) NULL`，只存导演计划生成时对应镜头输入的 SHA-256，不保存额外的旧字段全文。镜头序号、标识、时长、剧本、动作、对白、说话人、旁白、音效，以及锁定的景别/运镜参与计算；未锁定的摄影值、随机种子、镜头组与其他卡片不参与。文本去除首尾空白、空字符串归一为 null，字段顺序固定。

`POST /drama/shot/director-plan/check` 是只读检查接口，传入 `shotId`、当前 `directorPlanInput`，可携带待提交的计划和模式。返回 `inputsChanged`、`overwriteRequired`、当前输入哈希及计划版本。前端提示和弹窗依据服务端结果，实际任务提交时再次检查。

本地生成携带 `replanOnInputChange: true` 与完整当前输入。卡片未变化且计划修改时直接使用计划；卡片变化且计划未修改时直接重新规划；两者都变化时须先弹窗，确认后携带 `directorPlanOverwriteConfirmed: true`。取消不保存、不清除草稿、不启动任务。已保存 MANUAL 与未保存计划草稿都需要保护。输入中明确清空的文本不会被旧数据库值补回。

保存分镜和编辑已有计划不会刷新哈希；首次创建手工计划建立其输入基准。重新规划与提示词生成成功并通过校验后，计划、AUTO 模式、输入哈希和版本一起更新。重新规划失败不降级到使用旧计划。回填还要求提交时的计划版本、模式和数据库镜头输入仍匹配，避免覆盖任务执行期间的编辑。

迁移：`python scripts/migrate_director_plan_input_hash.py` 检查；加 `--apply` 备份并新增字段。历史计划缺少可靠基准时保留 NULL，首次本地生成重新规划建立基准；历史手工计划先确认覆盖。不要把当前镜头字段回填为历史计划输入，以免掩盖已发生的修改。

Ref2VA 和 FL2VA 共用 DirectorPlanEditor。默认 AUTO，未编辑按现有导演 AI → 提示词 AI 流程运行。只打开或取消编辑不会切换模式。保存或生成时提交修改后转为 MANUAL，后续生成直接将完整手工计划交给提示词 AI，直到明确恢复 AI 自动规划。

## 导演 AI 输出协议与失败诊断

`DirectorPlanSchema` 是导演提示词和校验器共享的协议定义，包含景别、机位、运镜、速度的完整合法值、必填字段与节拍数量约束。导演 AI 的纯 JSON 输出先检查必填字段和类型，再执行已有的物理归一化、摄影锁定和枚举校验；手工计划继续严格校验。Java 不将 `PULL_BACK` 等同义标签猜测映射成合法枚举。

导演规划只执行一次，不在解析或校验失败后追加 AI 修正请求。既有 Skill Tool Calling 流程保留；模型提供商的网络重试配置保持不变。重新规划失败时提示词 AI 不执行，原计划、输入哈希与版本不更新。

日志按 `taskId`、`shotId` 记录未经修改的完整响应（JSON 转义以保留单行日志）、具体字段路径和错误值、实际 Skill 版本追踪以及最终归一化结果。`RESPONSE_RECEIVED` 只表示收到响应，只有通过校验才记录 `SUCCESS`；接口异常为 `CALL_FAILED`，内容错误为 `VALIDATION_FAILED`。使用现有滚动日志，无新增数据库字段。

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
