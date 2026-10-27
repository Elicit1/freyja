# 音频素材与镜头参考

资产中心新增「音频素材」(`/assets/audios`)，复用视频素材库的卡片/表格视图、公共库/短剧/分镜归属、分页筛选、排序、启停、编辑与删除流程。支持试听、上传和维护原语言的对白/歌词文本。镜头编辑、AI 提示词生成、多模态渲染工坊统一提供素材选择器，也可继续选择配音中心角色声音。

## H3 语义

依据 [MiniMax H3 官方全参考模式文档 §2.4、§4.2、§5.4](https://github.com/MiniMax-AI/MiniMax-H3/blob/main/skills/h3-prompt-writing/references/ref-en.txt)。以下种类是 FREYJA 用于整理素材的分类，覆盖文档所述用途，不冒充模型协议枚举。

| audioKind | 素材种类 | 对应用途 |
| --- | --- | --- |
| VOICE | 音色与表达 | timbre、delivery、情绪 |
| DIALOGUE | 对白与旁白 | 对白内容与原声 |
| LYRICS | 歌词与歌声 | 歌词内容与歌声 |
| MUSIC | 背景音乐 | 音乐风格或信号 |
| SOUND_EFFECT | 音效与环境声 | 声音质感、环境氛围 |
| RHYTHM | 节拍与节奏 | beat、rhythm |
| CONTINUITY | 声音连续性 | audio continuity |
| FULL_TRACK | 完整音轨 | 多声音层的整体音轨 |

同一素材可以承担多种用途，具体使用方式由每个镜头的 `referenceDescription` 明确。素材种类不会自动选择或修改参考关系。镜头参考关系使用 H3 的四个原值：

- `fully_copy`：完整源音频作为目标视频的完整最终音轨。
- `partially_copy`：只复制部分时间线/声音层，或复制后添加、删除、替换声音。
- `reference`：不复制原始信号，仅参考明确指定的音色、节奏、音乐风格、对白内容或声音质感。
- `weak_reference`：仅保留类别或氛围的宽泛相似性。

仅参考音色、节奏或表达时，不自动沿用旧台词。精确复用或重新演绎对白/歌词时，应提供原语言原文。`characterId` 是可选的显式角色绑定，音乐、音效等无需角色。文本与分类均由创作者填写，Java 不猜测声音内容。

## API

所有响应使用 `R<T>`；雪花 ID 在 JSON 与前端均保持字符串。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/res/audio/page` | 分页；`current/size/name/dramaId/shotId/sourceType/audioKind/status` |
| GET | `/res/audio/{id}` | 素材详情与归属名称 |
| POST | `/res/audio/upload` | multipart：`file`、必填 `audioKind`；可选 `name/dramaId/shotId/sourceType/text/language` |
| PUT | `/res/audio` | 编辑 `id/name/audioKind/dramaId/shotId/description/text/language/sortOrder/status/remark`；文件 URL 不可修改 |
| DELETE | `/res/audio/{id}` | 逻辑删除；提交后仅清理未被其他素材、镜头或历史快照引用的文件 |

`availableDramaId` 专供镜头选择器：只返回公共库及当前短剧已启用素材；分页最大 100 条。`sourceType` 为 `MANUAL_UPLOAD` 或 `SHOT_UPLOAD`。`dramaId=0` 表示公共库。`language` 可省略或为 `zh/en/ja/other`。编辑时不填写 `shotId/text/language/description/remark` 会清空对应可选字段。

上传支持 MP3/WAV/FLAC/M4A/OGG，最大 30 MiB、10 分钟。FFprobe 校验真实容器、音轨及实际时长，拒绝含视频画面的文件；成功后归档 MinIO。上传与入库失败会清理未引用文件。

## 镜头与渲染

`refAudios` 新增 `resAudioId`（独立音频素材 ID）和 `audioKind`（素材种类快照）。配音中心继续使用 `audioId`；两个来源 ID 互斥。

```json
{
  "id": "audio-slot-1",
  "resAudioId": "2032095628944588801",
  "audioKind": "MUSIC",
  "audioUrl": "http://minio/freyja/audio-material/20261027/immutable.flac",
  "duration": 5.2,
  "name": "鼓点参考",
  "referenceType": "reference",
  "referenceDescription": "仅参考低鼓节拍和推进节奏，不复用原始音轨"
}
```

保存时验证素材存在、启用、归属公共库或当前短剧，URL 与归档一致，并以入库探测时长替换客户端时长。镜头和 Take 保存不可变 URL 快照；素材元数据修改不会改写历史快照。渲染前再次探测实际音频字节，所有来源共同遵守现有 H3 限制：最多 3 段、单段 2～15 秒、总长不超过 15 秒，并受所选模型 `maxAudios` 限制。当前参考工作流还需要参考图或参考视频，音频不可作为唯一参考模态。

音频顺序对应 `<Audio N>`。来源、种类、关系与说明进入 `ReferenceManifest`、AI 提示词生成、Java 的结构化 `references` 和网关的 `ReferenceBinding`；音频继续接入现有 H3 AUDIO 条件输入。完整复用等关系通过 H3 提示词表达，并非后端强行混音。

启动时使用幂等 SQL 自动创建 `res_audio`；独立升级脚本为 `src/main/resources/sql/asset-audio-library.sql`，Docker 首次初始化也包含该表。

## MCP 工具

新增六个工具，进入现有运行时工具目录、读写权限和能力版本管理；已有能力配置升级时补充新工具并保留原开关及草稿。

| 工具 | 参数与行为 |
| --- | --- |
| `search_audio_assets` | 传授权 `dramaId`，查询当前项目与公共库，默认仅启用；或 `publicOnly=true` 且省略 `dramaId`。支持 `keyword/audioKind/shotId/status/page/size`，每页最多 50 条 |
| `get_audio_asset` | `recordId`；可选 `dramaId/fields`，返回公开元数据与 `editFingerprint` |
| `update_audio_asset` | `requestId/recordId/expectedFingerprint/data`；可选 `dramaId/fields`。补丁仅修改元数据，省略保留、可选文本/语言/分镜关联传 `null` 清空；文件与项目不可替换 |
| `delete_audio_asset` | `requestId/recordId/expectedFingerprint`；可选 `dramaId`。逻辑删除，保留镜头历史快照 |
| `prepare_asset_audio_upload` | `requestId/dramaId/fileName/contentType/size/sha256/audioKind/referenceType`；可选 `shotId/referenceDescription/text/language`。申请 15 分钟私有暂存桶直传凭证 |
| `complete_asset_audio_upload` | 独立 `requestId`、原 `uploadId` 与授权 `dramaId`。固定 ETag 后校验大小、类型、SHA-256、真实音轨及 2～15 秒时长，入库并返回 `audioAssetId/url/reference` |

上传格式及 MIME：MP3=`audio/mpeg`、WAV=`audio/wav`、FLAC=`audio/flac`、M4A=`audio/mp4`、OGG=`audio/ogg`，最大 30 MiB。通过返回的 `uploadUrl/fields` 发送真实 multipart 文件，所有字段保持原样，`file` 最后发送。工具参数不接受本地文件路径或音频 Base64。一次流程对应一个音频；相同上传即使用不同确认请求 ID，也只入库一次。失败、超时可用原确认请求重试；事务回滚保留暂存文件以便恢复。

公共库通过 MCP 只读；上传、编辑、删除均须写权限及所属项目授权。编辑/删除前先读取指纹，冲突后检查当前值并用新请求 ID 重试。所有写请求遵守现有幂等操作日志，确认不自动改写镜头。

镜头引用步骤：

1. 查询 `get_business_options(dictType="audio_asset_kind")` 和 `reference_audio_type`，明确素材种类与 H3 关系。
2. 搜索并读取现有音频，或完成直传取得真实素材 ID、URL、时长；音乐/音效不要求角色。
3. 使用 `update_shot` 或 `batch_update_shots` 写入有序 `refAudios`；上传返回的 `reference` 可直接作为一项使用。数组整体替换，先读取并保留已有项，`[]` 明确清空。
4. 用 `get_shot` 读回核验；总长仍不超过 15 秒。仅在创作者明确要求时复用原声或引入配乐，音乐用途写入 `referenceDescription` 与最终 H3 提示词的 `non_diegetic_music`。MCP 镜头 CRUD 没有 `includeBgm` 字段；该开关仅属于工作台的提示词生成请求。

后端重启后刷新 MCP 工具列表即可发现新增入口；实际发布仍受服务总开关、写权限及各工具开关约束。
