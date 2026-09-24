# 标准 Agent Skills 包迁移

当前版本只导入标准 Agent Skills 目录的 ZIP 包。每个包的根目录必须包含带 YAML frontmatter 的 `SKILL.md`；旧的自由格式单文件 `.md` 不再兼容，也不会从旧的 Freyja 私有 `skill.yaml` 或旧文件表推断元数据。

## 新环境

直接执行 `src/main/resources/sql/init.sql`。系统会创建 `ai_skill_file`，入口 `SKILL.md` 正文保存于 `ai_skill_version.content`，其余包内文件保存于 MinIO `skills/` 前缀。

## 已有环境

1. 备份 `ai_skill`、`ai_skill_version`、`ai_skill_file` 表和旧 MinIO `skills/` 前缀。
2. 暂停 Skill 上传与导演规划流量。
3. 执行 `src/main/resources/sql/upgrade_ai_skill_standard_package.sql`。
4. 将要迁移的每个 Skill 整理为标准目录：根目录放置 `SKILL.md`，并在 frontmatter 中补齐 `name` 与 `description`；`references/`、`scripts/`、`assets/` 等目录按标准原样保留。
5. 将目录打包为 ZIP，通过 `POST /system/skills/upload` 重新导入。每次导入会生成新的版本、计算整个包的 SHA-256，并自动切换当前版本。
6. 校验 `/system/skills` 列表、版本预览中的文件索引及导演规划的 `load_skill` 调用后，再恢复流量。

历史版本如需保留，应按原版本顺序分别打包成标准 ZIP 后重新上传；系统会以 1、2、3… 生成新的递增 revision，并在外部迁移清单中记录旧版本号。
