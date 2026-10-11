# 单助手合并验收

2026-10-11，分支 `codex/agent-foundation`。已更新 WSL Docker 前后端，页面为 http://localhost/system/agent 。

系统只注册、展示和配置一个 FREYJA 助手（`core-assistant`），同时具备聊天、项目查询、明确记忆与小说制作准备能力。配置页移除助手切换列表，新建会话不再选择 Agent。制作入口使用主配置，旧制作会话、运行快照和业务结果保留。旧 `production-preparation` 标识通过别名兼容。

原主配置的模型和宠物已通过页面及接口对照确认保留；工具、技能与执行预算按版本一次性升级。本机主配置从 v8 升级到 v9，页面校验与保存后为 v10。配置列表与启用助手列表各只有一个条目。重复读取已升级配置不会再次合并旧配置。

后端相关回归 77 项：73 通过、4 个显式启用的 MySQL 集成测试跳过。覆盖能力合并、模型/宠物保留、旧标识兼容、新快照使用统一配置，以及制作完成校验。前端 TypeScript 与 Vite 生产构建通过，Docker 镜像构建和部署通过。

Chrome 页面检查 16 项通过，包含单配置、模型/宠物保持、配置校验保存、无 Agent 选择器、旧报告显示及真实 DeepSeek 旧会话查询。查询任务 `2109262775316824066`，会话 `2109220706684387329`，4 轮模型、8 次工具，完成后原邮局 6 镜规划逐字段保持一致，没有新增制作 Job。

使用同一个助手通过页面提交原创无人码头文本，复用本项目专用上传场景图。新会话 `2109263060445609986`，任务 `2109263060634353665`，8 轮模型、10 次工具完成。新任务标识为 `core-assistant`，使用主配置 v10，完成小说拆解、追加剧集、真实参考图关联和导演/最终提示词保存，1 镜 READY，待补图为 0，未生成视频。该流程 8 项检查全部通过。

本次示例数据、新增会话与测试结果全部保留。日志与截图在忽略的 `logs/`：`agent-single-browser-checks.json`、`agent-single-production-checks.json`、`agent-single-config.png`、`agent-single-old-session.png`、`agent-single-production-delivery.png`。历史第二阶段验收见 [原验收记录](agent-phase2-validation.md)。
