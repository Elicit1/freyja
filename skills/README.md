# 系统预置 Skills

本目录包含从本地 FREYJA 已发布版本导出的 ZIP，以及仓库维护的 `freyja-platform-guide` 平台使用技能、`mimo-speech-synthesis` 语音技能和 `story-writing` 故事创作与自审技能。包内包含完整 `SKILL.md` 和所需参考文件。导出脚本使用只读 API，不修改系统中的版本、开关或配置。

| Skill | 本地来源版本 | 文件数 | 职责 |
| --- | --- | --- | --- |
| `camera-direction` | 32 | 5 | Worker 连续剧情分镜编排、三维世界状态、自然动作与人物道具连续性 |
| `character-disambiguation` | 1 | 1 | Planner 角色身份、别名与上下文指代消歧 |
| `character-sheet-design` | 1 | 1 | 角色 / 造型设定图、三视图、头像、细节与背景规范 |
| `cinematography` | 49 | 6 | 视觉导演、可读机位、二维投影、景别运镜与 H3 动作编译 |
| `h3-prompt-writing` | 2 | 4 | H3 首尾帧三段式、参考模式六段式提示词与音画结构 |
| `mimo-speech-synthesis` | 仓库源文件 | 2 | MiMo 请求 JSON、音色设计与克隆、风格标签与局部音频标签 |
| `freyja-platform-guide` | 仓库源文件 | 7 | 故事自审入口、平台概念、叙事层级、参考图组合、首尾帧/参考模式、表情剧本、声音空间与制作验收 |
| `story-writing` | 仓库源文件 | 3 | 人物动机、剧情因果、信息揭示、铺垫回收与写入前举证自审、修稿复查 |

来源版本是开发环境中的发布序号；新部署导入后会按自己的数据库生成版本号。ZIP 保留 `refs/`、`references/` 和 `agents/` 的完整内容，不依赖开发环境的数据库或 MinIO。

[builtin-manifest.json](builtin-manifest.json) 记录每个 ZIP 和文件的 SHA-256、文件大小及来源版本。ZIP 使用固定时间戳和排序，相同内容重复导出不会产生无意义的二进制变化。

## 同步当前本地版本

在仓库根目录执行，使用 Python 标准库即可：

```bash
python scripts/export_builtin_skills.py --base-url http://127.0.0.1:8080
```

通过前端代理访问时，使用 `--base-url http://localhost/api`。脚本固定每个 Skill 的当前版本 ID 下载，核验正文、所有文件与完整包的哈希；全部下载完成后再次核对发布指针，版本发生变化时停止并提示重试，只有检查通过才将包和清单写入项目。

## 打包与初始化

后端 Dockerfile 的 `COPY skills/ /app/skills/` 将这些包写入镜像；Compose 将项目 `skills/` 目录挂载到相同位置，部署时需要同步该目录。

后端启动后自动导入缺失或无有效发布版本的技能。已有有效发布版本及其启停状态保持原样；需要升级已有技能时，在 `/system/skills` 上传本目录对应 ZIP，创建新版本并切换生效。附属文件作为知识保存，系统不会自动执行其中的脚本。

`freyja-platform-guide` 的维护源为同名目录，发布包为 `freyja-platform-guide.zip`，包根目录直接包含 `SKILL.md` 与 `references/`。修改源后须同步重打 ZIP 和清单哈希；不要把外层目录打入包内。现有部署可直接在 AI Skills 页面导入该包。只有源文件变更不会改变运行中的已发布知识。

默认 MCP 指南、`list_skills` 说明和内部公共规则已加入该技能的读取指引。已有能力目录的规则与工具说明来自数据库发布快照，代码更新不会覆盖管理员的自定义内容；需要在 `/system/ai-capabilities` 将相应读取指引保存并发布。已发布规则中的通用 Skill 发现流程仍可通过技能名称和描述发现本技能。

`mimo-speech-synthesis` 的维护源与发布包同样位于本目录。配音工具通过技能名称引导 AI 按需读取；部署时同步 ZIP，新技能重启自动导入，已有平台指南需通过 AI Skills 导入新版 ZIP。

## 故事创作与自审

`story-writing` 教通过 MCP 使用平台的 AI 在当前任务中设计故事、写出草稿、举证审稿并修稿，无未解决的阻断问题后简短说明再保存。正文及两个参考文件均通过现有 `read_skill` / `read_skill_file` 读取；不增加后端审核接口，也不提供长期故事记忆。默认最多修订两轮，设定冲突未解时暂停相关剧本写入，纯查询、排序和媒体更新不触发故事重审。

修改仓库维护的技能后，在根目录重新打包并检查（使用固定时间戳和文件排序）：

```bash
python scripts/package_repository_skills.py story-writing freyja-platform-guide
python scripts/package_repository_skills.py --check story-writing freyja-platform-guide
```

此命令读取清单标为 `repository:skills/<name>` 的源目录，更新相应 ZIP 和哈希，不修改源文件、不访问服务。Markdown、文本、YAML 与 JSON 换行统一为 LF，避免 Windows/Linux 检出方式改变包哈希。上面的服务器导出命令会按服务器发布目录重建整个清单；尚未上线的新技能不要通过服务器导出来打包，否则可能丢失其清单条目。

新部署同步包后重启可自动导入；已有部署需导入 `story-writing.zip`，合并更新当前平台指南及能力规则后发布。仓库平台指南可能早于线上自定义版本，**不要直接用旧包覆盖线上新版**。逐步升级、可直接合并的规则及行为验收用例见 [故事 Skill 接入与验收](../docs/story-writing-skill.md)。
