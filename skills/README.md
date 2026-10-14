# 系统预置 Skills

本目录的 5 个 ZIP 包从当前本地 FREYJA 系统已发布版本导出，包含完整 `SKILL.md`、参考文件及附属元数据。导出过程使用只读 API，不修改系统中的版本、开关或配置。

| Skill | 本地来源版本 | 文件数 | 职责 |
| --- | --- | --- | --- |
| `camera-direction` | 32 | 5 | Worker 连续剧情分镜编排、三维世界状态、自然动作与人物道具连续性 |
| `character-disambiguation` | 1 | 1 | Planner 角色身份、别名与上下文指代消歧 |
| `character-sheet-design` | 1 | 1 | 角色 / 造型设定图、三视图、头像、细节与背景规范 |
| `cinematography` | 49 | 6 | 视觉导演、可读机位、二维投影、景别运镜与 H3 动作编译 |
| `h3-prompt-writing` | 2 | 4 | H3 首尾帧三段式、参考模式六段式提示词与音画结构 |

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
