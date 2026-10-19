# 配音中心

前端：`/dubbing/characters` 角色配音、`/dubbing/effects` 特效配置。角色资产抽屉仅保留跳转按钮；`?characterId=雪花ID` 可定位左侧角色所在分页。

## 升级

后端启动通过 `DubbingSchemaInitializer` 执行幂等脚本 `src/main/resources/sql/dubbing-center.sql`，创建两张表与六个特效模板，补齐 `model_request_json` 列（已有表同样适用），不再初始化情绪字典，将旧角色样音迁移为基础声音。新库也可在服务停机时手动执行该脚本；旧表的新增 JSON 列由启动器检查并补齐，手动升级需先执行 `ALTER TABLE character_voice_audio ADD COLUMN model_request_json JSON NULL`（已有列时跳过）。保留旧角色语音列作为只读投影，角色 CRUD 不再写入这些字段。

提供商中须启用对应的 `TTS` / `AUDIO` / `VOICE` 模型：

- `mimo-v2.5-tts-voicedesign`：无特效基础音色设计。
- `mimo-v2.5-tts-voiceclone`：使用基础音频克隆声音；参考文件以 `audio.voice=data:audio/wav;base64,...` 传入，消息由调用方 JSON 原样提供；使用方法见 `skills/mimo-speech-synthesis/SKILL.md`。

FFmpeg 与 FFprobe 复用 `freyja.media-tools` 配置。管理页面检测服务器实际支持的滤镜。无需新增 Maven 或 npm 依赖。

## REST

以下地址由前端通过 `/api` 代理访问；统一使用 `R` 返回结构。

| 方法 | 路径 | 参数 / 用途 |
|---|---|---|
| GET | `/dubbing/characters` | `current,size,name,dramaName,locateId`，人物/短剧模糊查询，短剧创建时间降序、角色创建时间降序；公共角色随后 |
| GET | `/dubbing/characters/{id}/base` | 独立基础音频，不受标签筛选影响 |
| GET | `/dubbing/characters/{id}/replacements` | 基础声音重新设计任务及失败状态 |
| GET | `/dubbing/characters/{id}/audios` | `current,size,emotions,effectIds`；类别间取交集，同类多标签取并集 |
| POST | `/dubbing/characters/{id}/audios` | `name,providerId,modelCode,method,modelRequest,effects,replaceBaseId?` |
| POST | `/dubbing/characters/{id}/upload-base` | multipart：`file,name,text,replaceBaseId?`；最大7 MiB，解码后最长30秒 |
| GET | `/dubbing/audios/{id}` | 配音详情与当前播放地址 |
| PUT | `/dubbing/audios/{id}` | `revision,name`，只修改名称 |
| PUT | `/dubbing/audios/{id}/effects` | `revision,effects`；`[]` 清空，非空异步从原声处理 |
| POST | `/dubbing/audios/{id}/retry` | `revision`；已有原声时只重试特效 |
| DELETE | `/dubbing/audios/{id}` | `revision`；基础声音级联删除克隆及音频文件 |
| GET | `/dubbing/effects` | `current,size,name,enabled?` |
| POST | `/dubbing/effects` | `id?,name,description,steps,enabled,version?`，新增或带版本更新 |
| GET | `/dubbing/effects/filters` | 20种处理、86个参数；分类、类型、上下界、初始值、枚举、单位与实际可用性，见[参数组件](voice-filter-parameters.md) |
| POST | `/dubbing/effects/preview` | multipart：`steps` JSON 与 `file` 或 `audioId`，返回临时 data URL，不创建存储对象 |
| GET | `/dubbing/models` | 已启用的 MiMo 声音设计/克隆模型，不返回密钥 |

`method` 为 `DESIGN` 或 `CLONE`。`DESIGN` 禁止携带特效；已有基础声音时须明确 `replaceBaseId`。创建/修改特效返回音频记录与 `taskId`，任务在原有任务中心显示，点击可返回对应角色。

页面默认使用普通表单：DESIGN 填写音色描述和试音台词，CLONE 填写配音台词及可选表演说明；台词内的标签原样保留。可切换“JSON 编辑”，提供“格式化 JSON”和“一键填充模板”；已有内容时填充模板需确认覆盖。表单仅修改对应消息的 content，保留其他参数和消息元数据。复杂多轮消息、系统消息或无 assistant 的自动试音请求保持 JSON 模式，切换失败不会丢弃内容。没有情绪下拉框或字典请求。声音特效默认折叠，选择框下不再展开参数卡片，特效模板在管理页面维护。

`modelRequest` 必须为对象，包含非空 `messages` 数组，消息 `role/content` 为字符串；最后一条 assistant.content 投影为只读 `text` 元数据，原样保留标签。不再接受旧生成参数 `text/voiceDesc/emotion` 作为生成依据。系统不识别标签、不增加表演指令。支持设计模型通过 `audio.optimize_text_preview=true` 省略 assistant，此时不编造 text。

系统绑定所选模型、基础声音、`audio.format=wav` 与 `stream=false`；可省略 model/audio/stream。JSON 中 model 如提供须与选择一致，禁止提供 audio.voice、其他格式或流式输出。其余参数原样传递。请求 JSON 最多64K字符，台词最多4000字；结构错误在提交阶段返回400。JSON 以 `modelRequestJson` 随记录保存，生成重试读取原快照；旧记录无 JSON 时需填写 JSON 创建新版本，已有原声的特效重试照常工作。历史 emotion/voice_desc 列保留，不再驱动生成；可以删除 voice_emotion 字典，重启不会重新写入。

效果选择：`{"effectId":"86101","steps":[{"type":"highpass","params":{"frequency":400}}]}`；省略 steps 使用模板当前参数。每条配音最多6个特效，总计最多24步。编辑既有配音时原参数不变则保留旧版本快照；主动使用最新模板才更新。

## 分镜参考音频选择

镜头配置、AI生成提示词、参考模式视频渲染共用音频选择弹窗，新增/替换都需要显式选择。只查询当前短剧角色，以及当前镜头已关联的公共角色。

| 方法 | 路径 | 参数 / 用途 |
|---|---|---|
| GET | `/dubbing/picker/characters` | 必填 `dramaId,characterIds`，可选 `current,size,name,preferredCharacterId`；角色分页与名称模糊查询 |
| GET | `/dubbing/picker/characters/{id}/base` | 必填 `dramaId,characterIds`；基础声音固定展示 |
| GET | `/dubbing/picker/characters/{id}/audios` | 必填 `dramaId,characterIds`；`current,size,emotions,effectIds`，克隆成品分页 |

所有 ID 保持字符串。查询服务同时校验实际角色归属与传入集合，不允许其他短剧角色进入结果。

分镜 `refAudios` 项结构：

```json
{"id":"slot-uuid","audioId":"2032095628944590001","characterId":"2032095628944588802","audioUrl":"https://archive/clone.wav","duration":5.2,"referenceType":"reference","referenceDescription":"参考声线和克制语气，不复用原台词"}
```

`referenceType` 必填：`fully_copy` 完整复用，`partially_copy` 部分复用，`reference` 参考且不复制信号，`weak_reference` 弱参考类别或氛围。保存中心引用时检查角色归属、READY 状态和当前成品 URL。`text` 仅为转录元数据，当前对白由 `scriptContent` 决定。最多3条、各2至15秒、合计不超过15秒。

音频移除 `sourceType` 与 `usageMode`，拒绝旧字段和旧枚举；不迁移、不映射、不补默认值。已有旧音频配置必须重新提交新结构。图片字段不变。视频网关中 `referenceType=REFERENCE_AUDIO` 仍是媒体传输类型，四种业务关系由 `audioReferenceType` 原样传递。

## 保存与任务语义

- `BASE`：角色唯一基础音频；数据库生成列唯一索引强制约束。
- `CLONE`：关联 `baseAudioId`，始终保留 `rawAudioUrl/rawDuration`，成品使用 `processedAudioUrl/processedDuration`。
- `BASE_CANDIDATE`：替换候选，成功后在事务内删除旧基础及其克隆，再成为 BASE；失败保留原基础。
- 状态：`GENERATING,PROCESSING,READY,FAILED,EFFECT_FAILED,CANCELLED`。
- TTS 成功即先归档原声；FFmpeg 失败可重试，不重复调用 TTS。清空特效直接播放原声。修改请求 JSON 生成新的克隆记录，保留旧版本。
- 特效更新增加 revision，任务完成时校验音频版本与 taskId。旧任务、删除或取消的任务不能恢复音频。
- 正在处理或处理失败时，playbackUrl 可返回原声；界面明确标识原声，不能将其标为已应用新特效。
- 分镜活跃引用按 URL 精确检查。被引用音频禁止删除/修改特效，先解除引用，避免静默改变分镜声音。历史视频 Take 快照保持原样。
- 新生成的配音中心文件按记录清理；迁移的旧共享文件保留，避免破坏历史引用。

## MCP 与 guide

新增 `dubbing_list_audios,dubbing_list_effects,dubbing_list_filters,dubbing_create_audio,dubbing_update_effects,dubbing_upload_base_audio,dubbing_get_audio,dubbing_delete_audio`。写工具需要启用写权限、授权短剧角色和稳定 requestId；配音写入与页面共用服务。基础音频上传使用实际 Base64 文件内容。

升级时将新工具加入统一能力目录，保留已有工具开关及管理员自定义内容。guide 源文件、reference、ZIP 与 manifest 同步更新；已有部署遵循现有 Skill 发布机制，在 AI Skills 导入 `skills/freyja-platform-guide.zip` 并切换生效，新部署自动导入。主参考流程见包内 `references/dubbing-center.md`。

MiMo 使用规范维护于 `skills/mimo-speech-synthesis/`，发布包 `skills/mimo-speech-synthesis.zip`。新部署自动导入；已有部署在 AI Skills 导入并发布新包，同时更新平台指南包。AI 使用前从技能目录读取，不在后台提示词中内置教程。
