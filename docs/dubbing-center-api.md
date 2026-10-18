# 配音中心

前端：`/dubbing/characters` 角色配音、`/dubbing/effects` 特效配置。角色资产抽屉仅保留跳转按钮；`?characterId=雪花ID` 可定位左侧角色所在分页。

## 升级

后端启动通过 `DubbingSchemaInitializer` 执行幂等脚本 `src/main/resources/sql/dubbing-center.sql`，创建两张表、情绪字典与六个特效模板，将旧角色样音迁移为基础声音。也可在服务停机时手动执行该脚本。保留旧角色语音列作为只读投影，角色 CRUD 不再写入这些字段。

提供商中须启用对应的 `TTS` / `AUDIO` / `VOICE` 模型：

- `mimo-v2.5-tts-voicedesign`：无特效基础音色设计。
- `mimo-v2.5-tts-voiceclone`：使用基础音频克隆声音；参考文件以 `audio.voice=data:audio/wav;base64,...` 传入，台词位于 assistant 消息，情绪指令位于 user 消息。

FFmpeg 与 FFprobe 复用 `freyja.media-tools` 配置。管理页面检测服务器实际支持的滤镜。无需新增 Maven 或 npm 依赖。

## REST

以下地址由前端通过 `/api` 代理访问；统一使用 `R` 返回结构。

| 方法 | 路径 | 参数 / 用途 |
|---|---|---|
| GET | `/dubbing/characters` | `current,size,name,dramaName,locateId`，人物/短剧模糊查询，短剧创建时间降序、角色创建时间降序；公共角色随后 |
| GET | `/dubbing/characters/{id}/base` | 独立基础音频，不受标签筛选影响 |
| GET | `/dubbing/characters/{id}/replacements` | 基础声音重新设计任务及失败状态 |
| GET | `/dubbing/characters/{id}/audios` | `current,size,emotions,effectIds`；类别间取交集，同类多标签取并集 |
| POST | `/dubbing/characters/{id}/audios` | `name,text,providerId,modelCode,method,voiceDesc?,emotion?,effects,replaceBaseId?` |
| POST | `/dubbing/characters/{id}/upload-base` | multipart：`file,name,text,replaceBaseId?`；最大7 MiB，解码后最长30秒 |
| GET | `/dubbing/audios/{id}` | 配音详情与当前播放地址 |
| PUT | `/dubbing/audios/{id}` | `revision,name`，只修改名称 |
| PUT | `/dubbing/audios/{id}/effects` | `revision,effects`；`[]` 清空，非空异步从原声处理 |
| POST | `/dubbing/audios/{id}/retry` | `revision`；已有原声时只重试特效 |
| DELETE | `/dubbing/audios/{id}` | `revision`；基础声音级联删除克隆及音频文件 |
| GET | `/dubbing/effects` | `current,size,name,enabled?` |
| POST | `/dubbing/effects` | `id?,name,description,steps,enabled,version?`，新增或带版本更新 |
| GET | `/dubbing/effects/filters` | 可用滤镜、参数上下界、初始值、步长 |
| POST | `/dubbing/effects/preview` | multipart：`steps` JSON 与 `file` 或 `audioId`，返回临时 data URL，不创建存储对象 |
| GET | `/dubbing/models` | 已启用的 MiMo 声音设计/克隆模型，不返回密钥 |

`method` 为 `DESIGN` 或 `CLONE`。`DESIGN` 禁止携带特效或情绪；已有基础声音时须明确 `replaceBaseId`。创建/修改特效返回音频记录与 `taskId`，任务在原有任务中心显示，点击可返回对应角色。

效果选择：`{"effectId":"86101","steps":[{"type":"highpass","params":{"frequency":400}}]}`；省略 steps 使用模板当前参数。每条配音最多6个特效，总计最多24步。编辑既有配音时原参数不变则保留旧版本快照；主动使用最新模板才更新。

## 保存与任务语义

- `BASE`：角色唯一基础音频；数据库生成列唯一索引强制约束。
- `CLONE`：关联 `baseAudioId`，始终保留 `rawAudioUrl/rawDuration`，成品使用 `processedAudioUrl/processedDuration`。
- `BASE_CANDIDATE`：替换候选，成功后在事务内删除旧基础及其克隆，再成为 BASE；失败保留原基础。
- 状态：`GENERATING,PROCESSING,READY,FAILED,EFFECT_FAILED,CANCELLED`。
- TTS 成功即先归档原声；FFmpeg 失败可重试，不重复调用 TTS。清空特效直接播放原声。修改台词/情绪生成新的克隆记录，保留旧版本。
- 特效更新增加 revision，任务完成时校验音频版本与 taskId。旧任务、删除或取消的任务不能恢复音频。
- 正在处理或处理失败时，playbackUrl 可返回原声；界面明确标识原声，不能将其标为已应用新特效。
- 分镜活跃引用按 URL 精确检查。被引用音频禁止删除/修改特效，先解除引用，避免静默改变分镜声音。历史视频 Take 快照保持原样。
- 新生成的配音中心文件按记录清理；迁移的旧共享文件保留，避免破坏历史引用。

## MCP 与 guide

新增 `dubbing_list_audios,dubbing_list_effects,dubbing_create_audio,dubbing_update_effects,dubbing_upload_base_audio,dubbing_get_audio,dubbing_delete_audio`。写工具需要启用写权限、授权短剧角色和稳定 requestId；配音写入与页面共用服务。基础音频上传使用实际 Base64 文件内容。

升级时将新工具加入统一能力目录，保留已有工具开关及管理员自定义内容。guide 源文件、reference、ZIP 与 manifest 同步更新；已有部署遵循现有 Skill 发布机制，在 AI Skills 导入 `skills/freyja-platform-guide.zip` 并切换生效，新部署自动导入。主参考流程见包内 `references/dubbing-center.md`。
