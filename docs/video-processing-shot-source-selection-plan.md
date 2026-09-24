# 超分与补帧工作台选择分镜视频实施计划

## 1. 目标

改造现有视频超分工作台和补帧工作台，使创作者除了上传本地视频、填写视频 URL 外，还可以直接从短剧分镜中选择已有视频作为处理源。

适用页面：

- `front/src/views/production/VideoUpscale.vue`
- `front/src/views/production/FrameInterpolation.vue`

核心原则：

1. “选择分镜视频”成为主要入口，本地上传继续保留为补充入口。
2. 前端提交分镜 ID / 视频 Take ID，后端解析并校验真实视频，不能信任前端传入的 URL。
3. `media_process_task.source_video_url` 继续保存任务提交时的不可变 URL 快照。
4. 分镜之后切换当前视频，不影响已经排队或执行中的后处理任务。
5. 后处理产物本次只进入后处理任务历史，不自动覆盖分镜当前视频。
6. 所有 Snowflake ID 在前端保持字符串，严禁 `Number(id)`、`parseInt(id)` 或一元 `+id`。
7. 超分和补帧模型必须配置在 AI 模型中心，工作台和后端不得写死模型编码、checkpoint 或引擎专属参数。

本文档供其他 AI 直接实施。

## 2. 当前问题

当前两个工作台的源视频输入方式完全相同：

- 上传本地文件到 MinIO；
- 手工输入 MinIO / 外部 URL；
- 前端把 `sourceVideoUrl` 直接提交给 `/video-processing/tasks`；
- 后端只校验 URL 非空，不知道视频来自哪个短剧、剧集、场次或分镜。

由此带来的问题：

1. 已经在分镜中生成的视频仍需下载再上传或复制 URL。
2. 容易复制错视频，缺少镜头编号和上下文。
3. 后处理任务历史不能追溯来源分镜。
4. 手工 URL 是客户端提供的数据，后端缺少业务归属校验。
5. 超分和补帧页面各自维护相同上传逻辑，后续继续重复扩展。

## 3. 范围

### 3.1 必须实现

- 新增可复用的“分镜视频选择器”组件。
- 支持按短剧、剧集、场次筛选有视频的分镜。
- 支持关键字搜索镜头编号/名称。
- 默认选择分镜当前视频。
- 提交后处理任务时保存来源类型和来源分镜 ID。
- 后端根据 `shotId` 解析当前视频，并校验视频存在。
- `media_process_task` 保存来源业务关联和提交时 URL 快照。
- 后处理任务同步关联 `render_task` 的 drama/episode/scene/shot 上下文。
- 选择分镜后自动执行视频元信息探测并回填 FPS。
- 超分和补帧共用同一套选择器与来源状态逻辑。
- 与“分镜视频 Take 历史”方案兼容。
- 模型中心完整支持 `VIDEO_UPSCALE`、`FRAME_INTERPOLATION` 类型。
- 工作台按提供商和操作类型加载已启用模型，后端通过 `modelId` 校验并解析模型配置。
- 更新 SQL、DTO、VO、接口文档、测试。

### 3.2 本次不实现

- 不把超分/补帧结果自动设为分镜当前视频。
- 不把后处理产物自动创建为原始生成 Take。
- 不实现整集/整场次批量超分或补帧。
- 不实现多个视频同时选择。
- 不删除上传视频或历史后处理产物。
- 不允许选择没有 `videoUrl` 的分镜。
- 不修改 FastAPI / ComfyUI 网关的视频处理协议。
- 不强制删除现有上传和 URL 输入能力。
- 不允许前端自由输入模型编码、checkpoint 文件名或网关内部模型参数。

“将超分/补帧结果应用回分镜”涉及原视频、派生视频和 Take 类型定义，应另行设计，不能在本任务中默认覆盖用户当前视频。

## 4. 来源模型

### 4.1 来源类型

后端新增枚举或常量：

```java
public enum VideoProcessSourceType {
    DIRECT_URL,
    SHOT_CURRENT,
    SHOT_VIDEO_TAKE
}
```

语义：

| 类型 | 请求提供 | 后端解析方式 |
|---|---|---|
| `DIRECT_URL` | `sourceVideoUrl` | 校验允许的 URL 后使用 |
| `SHOT_CURRENT` | `sourceShotId` | 查询 `drama_shot.video_url` / 当前 Take |
| `SHOT_VIDEO_TAKE` | `sourceShotId` + `sourceVideoTakeId` | 查询指定 Take，并验证属于该分镜 |

第一阶段必须完整支持 `DIRECT_URL` 和 `SHOT_CURRENT`。

`SHOT_VIDEO_TAKE` 与以下设计联动：

[分镜视频抽卡历史与历史版本重选实施计划](shot-video-take-history-plan.md)

如果 `drama_shot_video_take` 尚未实现，先隐藏“选择历史 Take”入口，但 DTO、数据库字段和服务结构应预留，不能让未来再次重构任务来源模型。

### 4.2 URL 快照原则

无论来源是什么，创建任务时都必须解析并固化：

```text
resolvedSourceVideoUrl
```

然后写入：

```text
media_process_task.source_video_url
```

异步任务只能使用该快照，不能在真正执行时重新读取 `drama_shot.video_url`。

示例：

```text
10:00 用户选择 S03 当前视频 A
10:01 后处理任务入队，source_video_url = A
10:02 用户把 S03 当前视频切换为 B
10:03 后处理任务开始执行

正确结果：仍处理 A
错误结果：执行时重新查 S03，改成处理 B
```

### 4.3 模型中心配置（禁止写死）

模型中心统一使用：

```text
VIDEO_UPSCALE         视频超分模型
FRAME_INTERPOLATION   视频帧插值/补帧模型
```

`src/main/resources/sql/init.sql` 的 `ai_model_type` 字典数据已经存在这两个值，但当前 JavaDoc、前端 `ModelType`、模型管理下拉和标签映射尚未完整接入，实施时必须全部补齐。不得另外创建一套后处理模型配置表。

#### 4.3.1 任务通过 `modelId` 选择模型

`VideoProcessSubmitDTO` 新增：

```java
private Long modelId;
```

新工作台提交 `providerId + modelId`，不再允许用户自由输入 `modelCode`。后端必须：

1. 查询 `AiModel`，不存在返回 404。
2. 校验模型 `status == 1`。
3. 校验 `model.providerId == providerId`。
4. `VIDEO_UPSCALE` 操作只接受 `modelType == VIDEO_UPSCALE`。
5. `FRAME_INTERPOLATION` 操作只接受 `modelType == FRAME_INTERPOLATION`。
6. 从模型实体读取 `modelCode`，写入任务快照并发送网关。
7. 解析并校验 `paramsJson`，生成受控的网关参数。

为兼容旧调用，`modelCode` 可以暂时保留为废弃字段。旧调用只传 `providerId + modelCode` 时，后端必须从 `ai_model` 查到同提供商下已启用且类型匹配的唯一模型；查不到就报错，绝不能回退到代码常量。

`media_process_task` 保存 `model_id`，`model_code` 继续保存提交时的不可变模型编码快照。

#### 4.3.2 `paramsJson` 规范

复用 `ai_model.params_json`，在其中使用受控的 `videoProcessing` 对象。

超分模型配置示例：

```json
{
  "videoProcessing": {
    "engineModel": "RealESRGAN_x2plus.pth",
    "defaultScale": 2,
    "allowedScales": [2, 4],
    "defaultCrf": 16,
    "minCrf": 10,
    "maxCrf": 30,
    "defaultPreserveAudio": true,
    "filenamePrefix": "video_upscale/realesrgan_x2"
  }
}
```

补帧模型配置示例：

```json
{
  "videoProcessing": {
    "engineModel": "rife49.pth",
    "defaultMultiplier": 2,
    "allowedMultipliers": [2, 4],
    "defaultCrf": 19,
    "minCrf": 12,
    "maxCrf": 28,
    "defaultClearCacheFrames": 100,
    "minClearCacheFrames": 50,
    "maxClearCacheFrames": 300,
    "defaultPreserveAudio": true,
    "filenamePrefix": "video_rife/rife_48fps"
  }
}
```

这些只是管理员配置示例，不得复制成后端默认常量。建议新增：

```text
dto/video/VideoProcessingModelParams.java
service/VideoProcessingModelResolver.java
service/impl/VideoProcessingModelResolverImpl.java
```

禁止把 `paramsJson` 原样透传给网关。Resolver 只能读取白名单字段并构造 `extra_body`：

```text
VIDEO_UPSCALE:
  upscale_model <- engineModel
  force_rate
  frame_rate
  crf
  filename_prefix <- filenamePrefix

FRAME_INTERPOLATION:
  force_rate
  multiplier
  frame_rate
  crf
  clear_cache_after_n_frames
  batch_size
  filename_prefix <- filenamePrefix
```

如果 FastAPI 网关已经定义补帧 checkpoint 字段，则从 `engineModel` 映射到该字段；如果没有，继续由顶层 `modelCode` 路由，不能自行发明新协议字段。

#### 4.3.3 保存时校验

`AiModelServiceImpl.validate()` 应按模型类型校验 `paramsJson`：

- JSON 必须合法，`videoProcessing` 必须是对象。
- 超分模型的默认倍率必须包含在允许倍率中。
- 补帧模型的默认 multiplier 必须包含在允许值中。
- `minCrf <= defaultCrf <= maxCrf`。
- clear-cache 默认值必须处于配置范围。
- 网关所需的 `engineModel` / `filenamePrefix` 不能为空。

错误配置应在模型中心保存时被拒绝，不能拖到任务运行阶段。

模型中心前端为这两种类型展示结构化配置表单，并将结果序列化到 `paramsJson`。可以提供高级 JSON 预览，但不应要求管理员手写 JSON。

#### 4.3.4 工作台加载模型

提供商选择后调用专用安全接口：

```http
GET /video-processing/models?providerId={providerId}&operation={operation}
```

返回已经解析和裁剪的模型选项，不让工作台直接解释任意 `paramsJson`：

```ts
interface VideoProcessingModelOption {
  id: string
  providerId: string
  modelCode: string
  modelName: string
  modelType: 'VIDEO_UPSCALE' | 'FRAME_INTERPOLATION'
  config: {
    defaultScale?: number
    allowedScales?: number[]
    defaultMultiplier?: number
    allowedMultipliers?: number[]
    defaultCrf: number
    minCrf: number
    maxCrf: number
    defaultClearCacheFrames?: number
    minClearCacheFrames?: number
    maxClearCacheFrames?: number
    defaultPreserveAudio: boolean
  }
}
```

该接口内部使用 `VideoProcessingModelResolver`，只返回已启用、属于指定提供商且类型与 operation 匹配的模型。`engineModel`、`filenamePrefix` 等网关内部参数不暴露给工作台。

- 下拉只显示已启用且类型匹配的模型。
- 提供商切换时清空旧 `modelId` 并重新加载。
- 可按数据库 `sortOrder` 自动选择第一项，但不能写死某个 model code。
- 没有兼容模型时禁用提交并提示前往模型中心配置。
- 选中模型后按接口返回的安全 `config` 设置倍率、CRF、缓存周期等 UI 默认值和范围。
- 前端不直接解析原始 `paramsJson`。
- 后端必须重复执行范围校验，不能信任前端。

#### 4.3.5 必须移除的硬编码

至少清理：

- `VideoProcessingServiceImpl` 中的 `realesrgan-x2-video`、`rife49-video-48fps`、`RealESRGAN_x2plus.pth`、固定 `filename_prefix` 和固定品牌任务名称。
- `VideoUpscale.vue` 中固定模型编码、checkpoint、RealESRGAN 标题/按钮/水印。
- `FrameInterpolation.vue` 中固定模型编码、checkpoint 和 RIFE 品牌文案。

页面保留通用标题“视频超分辨率增强”“视频帧插值/补帧”，具体模型名称从当前选中的模型动态展示。

## 5. 数据库设计

项目没有 Flyway/Liquibase，实施时必须：

1. 更新 `src/main/resources/sql/init.sql`；
2. 新增现有数据库升级脚本，例如：
   `src/main/resources/sql/upgrade_video_process_shot_source.sql`；
3. 不使用 `@PostConstruct` 动态修改表结构。

### 5.1 修改 `media_process_task`

增加：

```sql
ALTER TABLE `media_process_task`
    ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'DIRECT_URL'
        COMMENT '源视频类型: DIRECT_URL/SHOT_CURRENT/SHOT_VIDEO_TAKE'
        AFTER `operation`,
    ADD COLUMN `source_drama_id` BIGINT UNSIGNED DEFAULT NULL
        COMMENT '源视频所属短剧ID'
        AFTER `source_video_url`,
    ADD COLUMN `source_episode_id` BIGINT UNSIGNED DEFAULT NULL
        COMMENT '源视频所属剧集ID'
        AFTER `source_drama_id`,
    ADD COLUMN `source_scene_id` BIGINT UNSIGNED DEFAULT NULL
        COMMENT '源视频所属场次ID'
        AFTER `source_episode_id`,
    ADD COLUMN `source_shot_id` BIGINT UNSIGNED DEFAULT NULL
        COMMENT '源视频所属分镜ID'
        AFTER `source_scene_id`,
    ADD COLUMN `source_video_take_id` BIGINT UNSIGNED DEFAULT NULL
        COMMENT '指定来源视频Take ID，SHOT_VIDEO_TAKE时使用'
        AFTER `source_shot_id`,
    ADD COLUMN `model_id` BIGINT UNSIGNED DEFAULT NULL
        COMMENT '模型中心AI模型ID'
        AFTER `source_video_take_id`,
    ADD KEY `idx_vp_source_shot` (`source_shot_id`, `submit_time`),
    ADD KEY `idx_vp_source_take` (`source_video_take_id`),
    ADD KEY `idx_vp_model_id` (`model_id`),
    ADD KEY `idx_vp_source_drama_episode` (`source_drama_id`, `source_episode_id`);
```

字段含义：

- `source_*_id` 保存提交时来源关系，便于任务历史筛选和追溯。
- `source_video_url` 保存实际处理的 URL 快照。
- `SHOT_CURRENT` 提交时，如果当前视频由 Take 管理，也建议同时写入 `source_video_take_id = currentVideoTakeId`，以精确记录当时版本。
- `DIRECT_URL` 的所有来源业务 ID 为空。

### 5.2 旧数据兼容

升级后旧任务统一保留默认值：

```text
source_type = DIRECT_URL
source_*_id = null
model_id = null
```

不要根据 URL 猜测历史任务来自哪个分镜，避免错误关联。

## 6. 后端请求与响应设计

### 6.1 修改 `VideoProcessSubmitDTO`

新增：

```java
/** 来源类型，默认 DIRECT_URL */
private String sourceType;

/** SHOT_CURRENT / SHOT_VIDEO_TAKE 的来源分镜 */
private Long sourceShotId;

/** SHOT_VIDEO_TAKE 的指定 Take */
private Long sourceVideoTakeId;

/** 模型中心模型ID；新调用必填 */
private Long modelId;
```

保留：

```java
private String sourceVideoUrl;
```

联合校验规则：

```text
DIRECT_URL:
  sourceVideoUrl 必填
  sourceShotId/sourceVideoTakeId 必须为空

SHOT_CURRENT:
  sourceShotId 必填
  sourceVideoUrl 不作为可信输入，忽略或要求为空
  sourceVideoTakeId 可为空；后端从当前分镜解析

SHOT_VIDEO_TAKE:
  sourceShotId 必填
  sourceVideoTakeId 必填
  sourceVideoUrl 不作为可信输入，忽略或要求为空
```

推荐在 Controller/Service 中明确报错，不要通过“哪个字段非空”隐式猜来源类型。

### 6.2 修改 `VideoProcessResultVO`

新增：

```java
private String sourceType;
private String sourceDramaId;
private String sourceEpisodeId;
private String sourceSceneId;
private String sourceShotId;
private String sourceVideoTakeId;
private String modelId;
private Integer sourceShotNo;
private String sourceShotName;
private String sourceDramaTitle;
private String sourceEpisodeTitle;
private String sourceSceneName;
```

实体中的 Long 转换到 VO 时沿用项目当前的字符串处理方式，不能返回会导致前端精度损失的数字。

任务分页列表中的名称字段可以批量补充；不要逐条执行多次数据库查询造成 N+1。

### 6.3 来源解析对象

建议新增内部不可变对象：

```text
dto/video/ResolvedVideoProcessSource.java
```

字段：

```java
private String sourceType;
private String videoUrl;
private Long dramaId;
private Long episodeId;
private Long sceneId;
private Long shotId;
private Long videoTakeId;
private Integer shotNo;
private String shotName;
```

新增服务：

```text
service/VideoProcessSourceResolver.java
service/impl/VideoProcessSourceResolverImpl.java
```

接口：

```java
ResolvedVideoProcessSource resolve(VideoProcessSubmitDTO dto);
```

不要把分镜解析逻辑继续堆入已经较长的 `VideoProcessingServiceImpl`。

## 7. 后端来源解析规则

### 7.1 `SHOT_CURRENT`

处理流程：

1. 查询 `DramaShot`，不存在返回 404。
2. 校验 `videoUrl` 非空，否则返回“该分镜尚未生成视频”。
3. 如果已实现 Take 历史：
   - `currentVideoTakeId` 非空时查询 Take；
   - 校验 Take 属于该分镜且状态 `AVAILABLE`；
   - 校验 `take.videoUrl` 与 `shot.videoUrl` 一致；
   - 保存 `sourceVideoTakeId`。
4. 校验 URL 指向可处理的视频对象。
5. 返回分镜的 drama/episode/scene/shot 关联和 URL 快照。

如果 `currentVideoTakeId` 与 `videoUrl` 不一致，应返回数据一致性错误，不要擅自选择其中一个。

### 7.2 `SHOT_VIDEO_TAKE`

处理流程：

1. 查询分镜和 Take。
2. 校验 `take.shotId == sourceShotId`。
3. 校验 Take 状态 `AVAILABLE` 且未逻辑删除。
4. 校验 `videoUrl` 非空和对象可访问。
5. 允许选择非当前 Take 做超分/补帧，但界面必须明确显示 Take 编号。
6. 保存指定 Take ID 和 URL 快照。

### 7.3 `DIRECT_URL`

兼容现有上传和手工 URL：

- 上传后的 MinIO URL 可直接使用。
- 对手工外部 URL 实施协议和主机白名单，不允许 `file:`、内网探测地址或其他协议。
- 最安全的首版策略是只允许本项目 MinIO bucket URL；如果必须保留外部 URL，配置明确的允许域名列表。
- 后端异步下载逻辑和 `/video-processing/probe` 都必须使用相同的 URL 安全校验器。

本任务不能继续扩大已有 SSRF 风险。

## 8. 修改任务提交逻辑

修改 `VideoProcessingServiceImpl.submitTask()`：

```text
校验 operation
  → sourceResolver.resolve(dto)
  → 得到 ResolvedVideoProcessSource
  → modelResolver.resolve(providerId, modelId, operation)
  → 得到已启用且类型匹配的模型、modelCode、默认值、范围和白名单网关参数
  → 创建 media_process_task
      source_type
      source_video_url（快照）
      source_drama_id
      source_episode_id
      source_scene_id
      source_shot_id
      source_video_take_id
      model_id
  → 创建 render_task
      dramaId
      episodeId
      sceneId
      shotId
      taskName 包含镜头信息
  → 异步执行时只传 resolved videoUrl
```

`RenderTaskVO.taskName` 示例：

```text
[视频后处理][S03 夜路追逐] 视频超分（模型展示名）
[视频后处理][S03 夜路追逐] 24→48 FPS 补帧（模型展示名）
```

`DIRECT_URL` 没有镜头信息时继续使用当前通用名称。

### 8.1 不变快照

传给 `executeVideoProcessAsync()` 的必须是已经解析后的 `resolvedSource.getVideoUrl()`：

```java
final String resolvedVideoUrl = resolvedSource.getVideoUrl();
```

不能继续传 `dto.getSourceVideoUrl()`，否则 `SHOT_CURRENT` 没有前端 URL 时会失败，也会绕开后端解析结果。

## 9. 分镜视频选择器查询接口

不建议让前端加载整棵 Drama Tree 后自行筛选，因为数据量可能很大，且会把筛选、分页、Take 一致性逻辑放到前端。

建议新增专用只读接口。

### 9.1 查询可选分镜视频

```http
GET /video-processing/source-shots
```

查询参数：

```text
dramaId      可选
episodeId    可选
sceneId      可选
keyword      可选，匹配 shotName / shotNo
current      默认 1
size         默认 20，最大 50
```

只返回：

```text
deleted = 0
且 video_url 非空
```

响应 VO：

```java
public class ShotVideoSourceOptionVO {
    private String dramaId;
    private String dramaTitle;
    private String episodeId;
    private Integer episodeNo;
    private String episodeTitle;
    private String sceneId;
    private Integer sceneNo;
    private String sceneName;
    private String shotGroupId;
    private String shotId;
    private Integer shotNo;
    private String shotName;
    private BigDecimal duration;
    private String generationMode;
    private String videoUrl;
    private String posterUrl;
    private String currentVideoTakeId;
    private Integer videoTakeCount;
    private LocalDateTime videoUpdatedTime;
}
```

`posterUrl` 优先使用 `previewImageUrl`，不为选择器额外执行 ffmpeg 抽帧。

### 9.2 筛选选项

短剧、剧集、场次下拉可以复用现有 API，但需要修正相关函数的 ID 类型为 `string | number`。

为了减少级联请求，也可增加：

```http
GET /video-processing/source-context/options
```

首版不是必须。优先复用：

- `dramaApi.getOptions()`
- `episodeApi.getListByDramaId()`
- `dramaSceneApi.getListByEpisodeId()`

但不得对返回的 ID 做数字转换。

### 9.3 Take 历史展开

如果视频 Take 功能已实现，选择器中每个分镜提供：

```text
当前视频
查看该镜头其他历史版本（N）
```

展开后调用：

```http
GET /drama/shot/{shotId}/video-takes
```

选择当前版本时提交 `SHOT_CURRENT`；选择指定历史 Take 时提交 `SHOT_VIDEO_TAKE`。

不要在 `/source-shots` 列表中嵌入所有 Take，避免响应爆炸。

## 10. 前端共享组件设计

新增：

```text
front/src/views/production/components/ShotVideoPickerDialog.vue
```

Props / Expose 建议：

```ts
export interface SelectedVideoProcessSource {
  sourceType: 'SHOT_CURRENT' | 'SHOT_VIDEO_TAKE'
  sourceVideoUrl: string       // 仅用于本地预览，不作为后端可信提交字段
  sourceDramaId: string
  sourceEpisodeId?: string
  sourceSceneId?: string
  sourceShotId: string
  sourceVideoTakeId?: string
  dramaTitle?: string
  episodeTitle?: string
  sceneName?: string
  shotNo?: number
  shotName?: string
  takeNo?: number
  posterUrl?: string
}

defineExpose({ open })
emit('selected', source)
```

组件职责：

- 加载、筛选、分页展示可选分镜视频；
- 可选地展开 Take 历史；
- 播放预览；
- 返回结构化来源选择。

组件不负责：

- 修改分镜当前视频；
- 提交超分/补帧任务；
- 上传本地视频；
- 把 ID 转换为 Number。

## 11. 选择器 UI

建议 Dialog 宽度 960px：

```text
┌────────────────────────────────────────────────────┐
│ 选择分镜视频                                      │
├────────────────────────────────────────────────────┤
│ 短剧 [全部▼]  剧集 [全部▼]  场次 [全部▼] [搜索] │
├────────────────────────────────────────────────────┤
│ [首帧]  第1集 / 场次2 / S03 夜路追逐             │
│         5秒 · 首尾帧模式 · 当前视频 · 3个历史    │
│         [播放预览] [选择当前视频] [历史版本]      │
│                                                    │
│ [首帧]  第1集 / 场次2 / S04 回头                 │
│         ...                                        │
├────────────────────────────────────────────────────┤
│ 分页                                               │
└────────────────────────────────────────────────────┘
```

交互要求：

- 初次打开不自动播放视频。
- 点击预览后才加载对应视频，列表视频使用 `preload="metadata"`。
- 选中后关闭 Dialog，并在来源卡片显示镜头上下文而不是一长串 URL。
- 更换短剧时清空剧集和场次筛选。
- 更换剧集时清空场次筛选。
- 搜索和筛选回到第 1 页。
- 没有视频时显示“当前筛选范围内没有已生成视频的分镜”。

## 12. 改造超分与补帧来源卡片

两个页面都使用统一布局，不再让上传框成为唯一视觉中心：

```text
1. 选择源视频

[ 从分镜选择 ]  [ 本地上传 ]  [ URL（高级） ]

已选择：
《短剧名》 / 第1集 / 场次2 / S03 夜路追逐
[视频播放器或首帧]  当前视频 Take #4
[重新选择] [清除]
```

建议使用 `el-segmented`、tabs 或按钮组切换三种输入方式：

```ts
type SourceInputMode = 'SHOT' | 'UPLOAD' | 'URL'
```

默认模式：`SHOT`。

### 12.1 前端状态

不要继续只使用一个裸 `sourceVideoUrl` 表示全部来源。建议：

```ts
const selectedSource = ref<SelectedVideoProcessSource | null>(null)

const formData = reactive({
  sourceType: 'SHOT_CURRENT' as 'DIRECT_URL' | 'SHOT_CURRENT' | 'SHOT_VIDEO_TAKE',
  sourceVideoUrl: '',
  sourceShotId: '' as string,
  sourceVideoTakeId: '' as string,
  modelId: '' as string,
  // 现有处理参数...
})
```

切换输入模式时必须清理不属于该模式的字段：

```text
SHOT:
  清空手工 URL，等待选择 shot/take

UPLOAD/URL:
  sourceType = DIRECT_URL
  清空 sourceShotId/sourceVideoTakeId/selectedSource
```

避免同时携带 URL 和 shotId 导致后端来源歧义。

### 12.2 选择成功

```ts
function handleShotVideoSelected(source: SelectedVideoProcessSource) {
  selectedSource.value = source
  formData.sourceType = source.sourceType
  formData.sourceVideoUrl = source.sourceVideoUrl // 只用于播放器和探测
  formData.sourceShotId = source.sourceShotId
  formData.sourceVideoTakeId = source.sourceVideoTakeId || ''
  currentTask.value = null
  videoProbe.value = null
  activeViewMode.value = 'original'
  triggerProbeForSelectedSource()
}
```

探测接口最好也提交来源引用，详见第 13 节。前端展示 URL 不代表提交任务时可以信任它。

### 12.3 提交参数

分镜当前视频：

```json
{
  "operation": "VIDEO_UPSCALE",
  "sourceType": "SHOT_CURRENT",
  "sourceShotId": "2032095628944588801",
  "providerId": "2032000000000000001",
  "modelId": "2032000000000000101",
  "sourceFps": 24,
  "targetFps": 24,
  "scale": 2,
  "crf": 16,
  "preserveAudio": true
}
```

指定 Take：

```json
{
  "operation": "FRAME_INTERPOLATION",
  "sourceType": "SHOT_VIDEO_TAKE",
  "sourceShotId": "2032095628944588801",
  "sourceVideoTakeId": "2032095628944588999",
  "providerId": "2032000000000000001",
  "modelId": "2032000000000000102",
  "sourceFps": 24,
  "targetFps": 48,
  "multiplier": 2,
  "crf": 19,
  "preserveAudio": true
}
```

分镜来源提交时不要发送 `sourceVideoUrl`，防止前端 URL 与后端实体不一致。

## 13. 视频探测接口改造

当前接口：

```http
POST /video-processing/probe
{ "videoUrl": "..." }
```

建议新增统一接口：

```http
POST /video-processing/probe-source
```

请求复用来源字段：

```json
{
  "sourceType": "SHOT_CURRENT",
  "sourceShotId": "2032095628944588801"
}
```

后端使用同一个 `VideoProcessSourceResolver` 解析 URL，再调用 `probeVideoInfo`。

好处：

- 探测和正式提交使用相同来源校验；
- 分镜视频 URL 不依赖前端回传；
- Take 归属校验只实现一次。

保留旧 `/probe` 兼容 `DIRECT_URL`，内部也必须走 URL 安全校验。

## 14. 任务历史展示

超分和补帧历史抽屉中增加“来源”列：

```text
S03 夜路追逐
Take #4
```

对于旧任务或上传任务显示：

```text
上传/URL
```

选择历史任务“载入预览”时：

- 仍使用任务的 `sourceVideoUrl` 快照展示原视频；
- 不重新查询分镜当前视频；
- 不改变当前来源选择器的分镜绑定，除非用户明确点击“再次使用此来源”；
- 如果提供“再次处理”按钮，应按任务记录的来源类型和来源 ID 构造新提交，同时由后端重新校验来源是否仍有效。

## 15. 输出视频与分镜的关系

本次处理完成后：

- `media_process_task.output_video_url` 保存后处理产物；
- 不更新 `drama_shot.video_url`；
- 不更新 `drama_shot.current_video_take_id`；
- 不创建 `AI_GENERATED` 原始视频 Take；
- 不触发上一镜尾帧缓存失效。

原因：超分/补帧产物是派生资产，是否替换分镜当前视频必须由用户明确决定。

未来可新增“应用到分镜”功能，并将 Take 增加：

```text
source_type = UPSCALE_DERIVED / INTERPOLATION_DERIVED
parent_take_id
media_process_task_id
```

但这不是本任务范围。

## 16. 安全与权限边界

### 16.1 业务归属

后端必须验证当前用户有权访问所选短剧/分镜。项目尚未接入完整权限体系时，也要把检查集中在 Resolver 中，预留权限方法，不能完全依赖前端筛选。

### 16.2 URL 安全

- 分镜/Take 来源只接受数据库中已经归档的 URL。
- MinIO bucket 必须匹配系统配置。
- 手工 URL 必须使用统一允许列表。
- 禁止 `file:`、`ftp:`、`jar:` 等协议。
- 禁止通过重定向绕过主机校验。
- 探测和真正处理执行相同安全策略。

### 16.3 ID 精度

需要触碰的前端 API 参数全部改为：

```ts
string | number
```

组件内部立即使用：

```ts
String(id)
```

只能做字符串规范化，禁止转换成 JS Number。

## 17. 后端测试计划

### 17.1 `VideoProcessSourceResolverTest`

至少覆盖：

1. `DIRECT_URL` 缺少 URL。
2. `DIRECT_URL` 携带 shotId 时拒绝歧义参数。
3. `SHOT_CURRENT` 缺少 shotId。
4. 分镜不存在。
5. 分镜没有视频。
6. 正确解析当前分镜视频及上下文 ID。
7. 当前 Take 与 `videoUrl` 一致时解析成功。
8. 当前 Take 与 `videoUrl` 不一致时拒绝。
9. `SHOT_VIDEO_TAKE` 缺少 takeId。
10. Take 不属于指定分镜时拒绝。
11. Take 不可用时拒绝。
12. 非法外部 URL / 非本 bucket URL 被拒绝。
13. Snowflake ID 字符串能正确反序列化。

### 17.2 `VideoProcessingServiceTest`

至少覆盖：

1. 创建任务时保存 `sourceType` 和全部来源 ID。
2. 保存解析后的 URL 快照，而不是前端伪造 URL。
3. `SHOT_CURRENT` 创建的 RenderTask 带 drama/episode/scene/shot ID。
4. 异步执行使用 URL 快照。
5. 分镜在任务入队后切换视频，不影响本任务输入。
6. DIRECT_URL 旧调用仍兼容。
7. 任务 VO 正确返回来源上下文。
8. 后处理成功不修改 `drama_shot.videoUrl`。
9. 任务保存 `modelId` 与 `modelCode` 快照。
10. 网关参数中的 checkpoint 和 filenamePrefix 来自模型配置，而不是代码常量。

### 17.3 `VideoProcessingModelResolverTest`

至少覆盖：

1. modelId 不存在。
2. 模型已停用。
3. 模型不属于所选提供商。
4. 超分任务选择补帧模型时拒绝。
5. 补帧任务选择超分模型时拒绝。
6. `paramsJson` 非法或缺少必填配置时拒绝。
7. 正确解析 modelCode、默认值、范围和白名单网关参数。
8. 用户提交的 scale/multiplier/CRF 超出模型配置范围时拒绝。
9. 旧 modelCode 调用只能从模型中心解析，不能使用硬编码回退。

### 17.4 Controller 测试

- `/source-shots` 只返回有视频的分镜。
- drama/episode/scene 筛选正确。
- 关键字和分页正确。
- 最大 page size 被限制。
- `/probe-source` 与任务提交使用同样的来源解析。

## 18. 前端验证计划

两个工作台都要验证：

1. 默认显示“从分镜选择”。
2. 可按短剧、剧集、场次筛选。
3. 只显示已有视频的分镜。
4. 选择后显示短剧/剧集/场次/镜头名称。
5. 选择后自动探测分辨率、FPS、时长和音频。
6. 超分把目标 FPS 默认同步为源 FPS。
7. 补帧按源 FPS 和 multiplier 重算目标 FPS。
8. 可切换到本地上传，旧分镜来源字段被清空。
9. 可切换到 URL 模式，旧分镜来源字段被清空。
10. 分镜模式提交请求不携带可信 `sourceVideoUrl`。
11. 超大 Snowflake ID 没有精度损失。
12. 选择其他历史 Take 时显示正确 Take 编号。
13. 当前视频在选择后变化，不会改变已经提交任务的原片预览。
14. 历史任务正确显示来源镜头。
15. 后处理成功不会自动替换分镜当前视频。
16. 提供商切换后只加载匹配操作类型的启用模型。
17. 没有兼容模型时禁止提交并提示前往模型中心配置。
18. 模型选择控件不存在自由输入和硬编码默认 code。
19. scale、multiplier、CRF 等默认值及范围来自模型 `paramsJson`。

## 19. 文档更新

更新或新增：

- 新增 `docs/video-processing-api.md`，记录提交、探测、来源选择接口。
- `Readme.md`：超分和补帧能力增加“可直接选择分镜当前视频/历史 Take”。
- `front/src/api/videoProcessing.ts` 注释。
- `VideoProcessSubmitDTO`、`MediaProcessTask`、`VideoProcessingService` JavaDoc。
- 超分和补帧页面帮助文案。
- 若 Take 功能已落地，同步引用 `docs/shot-video-take-history-plan.md`。
- `docs/ai-provider-api.md`：补充两种模型类型和 `paramsJson.videoProcessing` 规范。
- 模型中心 UI：补充视频超分/补帧类型、标签及结构化参数编辑。

## 20. 建议实施顺序

1. 更新 `media_process_task` 初始化 SQL和升级 SQL，增加来源字段与 `model_id`。
2. 补齐模型中心的两种模型类型、结构化配置 UI 和保存校验。
3. 修改 Entity、SubmitDTO、ResultVO。
4. 实现 `VideoProcessingModelResolver` 和单元测试。
5. 实现 `VideoProcessSourceResolver` 和单元测试。
6. 改造 `VideoProcessingServiceImpl.submitTask()`，固化来源、模型快照和 RenderTask 上下文。
7. 移除前后端所有模型 code、checkpoint、filenamePrefix 和模型品牌硬编码。
8. 实现 `/video-processing/source-shots`。
9. 实现 `/video-processing/probe-source`。
10. 增加前端类型和 API。
11. 实现共享 `ShotVideoPickerDialog.vue`。
12. 改造 `VideoUpscale.vue`，从模型中心加载 `VIDEO_UPSCALE`。
13. 改造 `FrameInterpolation.vue`，从模型中心加载 `FRAME_INTERPOLATION`。
14. 更新任务历史来源展示。
15. 如果 Take 模块已经落地，接入历史 Take 展开选择。
16. 更新文档并执行测试与构建。

## 21. 验证命令

后端使用 JDK 26 和 Maven Wrapper：

```powershell
$env:JAVA_HOME = "F:\program_file\jdk26"
.\mvnw.cmd test
```

前端：

```powershell
$env:PATH = "D:\program_file\mvn\nvm\v24.9.0;$env:PATH"
Set-Location front
npm run build
```

数据库升级后检查：

```sql
SELECT source_type, COUNT(*)
FROM media_process_task
WHERE deleted = 0
GROUP BY source_type;

SELECT COUNT(*) AS invalid_shot_source
FROM media_process_task t
LEFT JOIN drama_shot s ON s.id = t.source_shot_id
WHERE t.source_type IN ('SHOT_CURRENT', 'SHOT_VIDEO_TAKE')
  AND (s.id IS NULL OR s.deleted = 1);
```

新增任务不应出现 `invalid_shot_source`；历史任务因分镜后来删除时允许保留任务记录，但界面应显示“来源分镜已删除”。

## 22. 完成标准

同时满足以下条件才算完成：

- 超分和补帧工作台都可以直接选择已有分镜视频。
- 用户无需下载并重新上传分镜视频。
- 后端根据分镜/Take ID解析视频，不信任客户端 URL。
- 任务表保存完整来源关系和提交时 URL 快照。
- 分镜切换当前视频不会影响已经提交的任务。
- 选择器只返回有视频的分镜，并支持分页筛选。
- 如果 Take 功能存在，可以选择当前视频或指定历史 Take。
- 后处理任务历史能显示来源镜头。
- 上传和 DIRECT_URL 旧流程仍可使用。
- 后处理结果不会未经用户确认覆盖分镜当前视频。
- 超分和补帧模型均来自模型中心，页面没有写死 model code。
- 后端没有写死 model code、checkpoint、filenamePrefix 或模型品牌。
- 提交任务通过 `modelId` 校验提供商、启用状态和模型类型。
- 模型默认参数和允许范围来自经过校验的 `paramsJson.videoProcessing`。
- 前端所有 Snowflake ID 无精度损失。
- 后端测试通过，前端生产构建通过。
- SQL、API 文档、页面文案和实际行为一致。
