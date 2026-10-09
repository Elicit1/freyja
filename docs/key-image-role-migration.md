# 关键图用途统一迁移

运行时仅支持 `FIRST_FRAME`、`KEYFRAME`、`LAST_FRAME`、`EDITED_KEYFRAME`、
`COMPOSITION_ANCHOR`、`STORYBOARD_REFERENCE`、`SHOT_PLANNING_REFERENCE`。
显式非法用途返回参数错误，不再进行旧值转换或自动回退。
槽位未填写用途时继承关联资产的类型；资产新增未填写类型时默认构图锚点。

首尾帧生图请求 `frameType` 只允许 `FIRST_FRAME`、`LAST_FRAME`，省略时为首帧。
前后端需要同步发布；数据库中的 `end_frame_*` 字段名称保留。

## 已有数据库

使用独立的 Python 3 / PyMySQL 迁移工具，先停止后端写入并等待在途任务结束。
不要重新执行包含 DROP TABLE 的初始化 SQL。

```powershell
# 默认只预检，不写入。
python scripts/migrate_key_image_roles.py --local-components docs/components.md

# 若预检发现旧关键图字典，核实编码后加 --dict-type <实际编码>，可重复传入。
python scripts/migrate_key_image_roles.py --local-components docs/components.md --apply
```

也可使用 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_USER`、`MYSQL_PASSWORD`、
`MYSQL_DATABASE` 及 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE`。
不在脚本中保存凭据。

迁移范围：

- `res_keyframe.frame_type`：旧尾帧名称统一为 `LAST_FRAME`，旧动作/运动关键图名称统一为 `KEYFRAME`。
- `drama_shot.ref_images_json`：只修改关键图槽位，保留顺序、ID 类型、URL 和其他字段。旧通用运动槽位优先继承关联资产的新用途，无关联资产时为 `KEYFRAME`。
- 明确指定的旧关键图字典：删除明细和类型，清除对应 `freyja:dict:<编码>` 缓存。
- 五个分镜/H3 提示词配置：替换旧用途标记，清除修改项的 `freyja:config:<配置键>` 缓存。

包含逻辑删除的业务记录，以便恢复记录后仍可使用。视频 Take、任务输入输出等历史快照不改写。
遇到未知类型、异常 JSON、在途任务或未确认字典编码时，预检失败，不写入。

应用前将全部待修改行原值和待删除字典记录保存到 `logs/migrations/key-image-roles-*.json`。
该目录被 Git 忽略；备份可能包含业务文本和媒体 URL，不应提交。
更新、字典删除和数据库校验在同一事务内进行，失败时回滚。
只有涉及字典或配置变更时才连接 Redis；停止写入期间先清缓存再提交事务。
完成后再次运行预检，更新数应为零，再启动新版本后端和前端。

若完整预检确认仅有已逻辑删除分镜需要迁移，可以使用 `--deleted-only --apply`，
该模式只锁定并修改已删除的分镜，不锁定活跃资产、字典或配置。
它拒绝资产、配置、字典变更，可在没有在途任务时修复当前工作台不可编辑的删除记录。

回滚时停止写入，按备份 `updates` 中各行的 `before` 和 `column` 恢复修改字段，
按 `deletedDictionaryTypes` / `deletedDictionaryEntries` 恢复删除记录，清理 `cacheKeys`，
然后同时恢复前后端旧版本。不要用旧备份覆盖迁移之后的新编辑。

## 验证

```powershell
python -m unittest discover -s scripts -p test_migrate_key_image_roles.py
$env:JAVA_HOME = 'F:\program_file\jdk26'
.\mvnw.cmd test '-Dtest=KeyImageRoleTest,KeyImageReferenceValidatorTest,ResKeyframeServiceTest,DramaShotServiceTest,ShotPromptDeriveH3Test,ShotAiVisualPlanServiceTest,ShotDualChannelPromptDeriveTest,DramaShotBackgroundPersistenceTest,AiImageApiServiceImplTest,AiEventTaskControllerTest'
$env:PATH = "D:\program_file\mvn\nvm\v24.9.0;$env:PATH"
cd front
npm run build
```
