# 短剧-剧集-情景-分镜 API 规范文档 (drama-api)

> 基础路径：`http://127.0.0.1:8080`
> 统一返回结构：`R<T>` (`code`: 200, `msg`: "success", `data`: T)

---

## 1. 短剧管理接口 (`DramaController` / `/drama`)

### 1.1 分页查询短剧列表
- **URL**: `GET /drama/page`
- **Query**: `current` (int), `size` (int), `title` (string), `genre` (string), `status` (string)
- `genre` 支持自定义题材文字或已有字典值，按完整值精确筛选；`drama_genre` 仅提供建议。
- **返回**: `R<Page<DramaVO>>` (含关联集数、场次数、总分镜数、已渲染分镜数、进度百分比)

### 1.2 获取短剧详情
- **URL**: `GET /drama/{id}`
- **返回**: `R<DramaVO>`

### 1.3 创建短剧
- **URL**: `POST /drama`
- **Body**: `DramaDTO`
```json
{
  "title": "霸道总裁爱上我",
  "coverUrl": "http://127.0.0.1:9000/video-assets/drama/cover.jpg",
  "genre": "DOMINANT_CEO",
  "targetEpisodes": 80,
  "aspectRatio": "9:16",
  "stylePreset": "cinematic-realism",
  "synopsis": "家族遭难的林晨隐藏战神身份，化身赘婿进入苏氏集团..."
}
```
- **返回**: `R<Long>` (新增短剧 ID)
- `genre` 为最多64字符的自由题材，如“都市复仇悬疑”。创建/编辑界面提供输入框与字典建议，点选已有建议仍保存原字典值，旧项目保持兼容。未收录的题材无需新增字典或编造枚举代码；题材属于短剧项目，剧集继承项目设定。

### 1.4 更新短剧
- **URL**: `PUT /drama`
- **Body**: `DramaDTO`

### 1.5 删除短剧
- **URL**: `DELETE /drama/{id}`
- **说明**: 逻辑删除短剧。

### 1.6 短剧下拉选项
- **URL**: `GET /drama/options`
- **返回**: `R<List<DramaOptionVO>>`

### 1.7 获取短剧完整大纲树 (四级聚合)
- **URL**: `GET /drama/tree/{id}`
- **返回**: `R<DramaTreeVO>` (包含 Drama -> EpisodeList -> SceneList -> ShotSummaryList 树形结构)

### 1.8 获取短剧生产统计概览
- **URL**: `GET /drama/stats/{id}`
- **返回**: `R<DramaStatsVO>` (包含总集数、场次数、总分镜数、成功渲染数、预估总时长等)
- **统计口径**: 只统计未删除短剧下、仍归属于有效剧集及场次的分镜；历史上删除父级后遗留的子分镜不计入总数。已渲染镜数按当前存在的视频 URL 计算，只有首帧图片不算视频完成。

---

## 2. 剧集管理接口 (`DramaEpisodeController` / `/drama/episode`)

### 2.1 获取某短剧的剧集列表
- **URL**: `GET /drama/episode/list/{dramaId}`
- **返回**: `R<List<DramaEpisodeVO>>` (按 `episodeNo` 升序排列，包含子场次与分镜统计)

### 2.2 获取剧集详情
- **URL**: `GET /drama/episode/{id}`
- **返回**: `R<DramaEpisodeVO>`

### 2.3 创建单集
- **URL**: `POST /drama/episode`
- **Body**: `DramaEpisodeDTO`

### 2.4 批量创建剧集
- **URL**: `POST /drama/episode/batch`
- **Body**:
```json
{
  "dramaId": 1,
  "startEpisodeNo": 1,
  "count": 10,
  "titlePrefix": "第",
  "titleSuffix": "集"
}
```
- **返回**: `R<List<Long>>`

### 2.5 更新剧集
- **URL**: `PUT /drama/episode`
- **Body**: `DramaEpisodeDTO`

### 2.6 删除剧集
- **URL**: `DELETE /drama/episode/{id}`

---

## 3. 情景场次管理接口 (`DramaSceneController` / `/drama/scene`)

### 3.1 获取某剧集的场次列表
- **URL**: `GET /drama/scene/list/{episodeId}`
- **返回**: `R<List<DramaSceneVO>>` (按 `sceneNo` 升序排列，包含关联环境资产详情与分镜数)

### 3.2 获取场次详情
- **URL**: `GET /drama/scene/{id}`
- **返回**: `R<DramaSceneVO>`

### 3.3 创建场次
- **URL**: `POST /drama/scene`
- **Body**: `DramaSceneDTO`

### 3.4 更新场次
- **URL**: `PUT /drama/scene`
- **Body**: `DramaSceneDTO`

### 3.5 删除场次
- **URL**: `DELETE /drama/scene/{id}`

---

## 4. 分镜镜头管理接口 (`DramaShotController` / `/drama/shot`)

### 4.1 获取某场次的分镜列表
- **URL**: `GET /drama/shot/list/{sceneId}`
- **返回**: `R<List<DramaShotVO>>` (按 `shotNo`/`sortOrder` 升序排列)

### 4.2 获取分镜详情
- **URL**: `GET /drama/shot/{id}`
- **返回**: `R<DramaShotVO>`

### 4.3 创建分镜
- **URL**: `POST /drama/shot`
- **Body**: `DramaShotDTO`
- `shotType` / `cameraMovement` 只有对应的 `shotTypeLocked` / `cameraMovementLocked` 为 `true` 且值不是 `AUTO` 时，才作为 Prompt AI 的创作者硬约束。Worker 新生成分镜将两项设为 `AUTO`；`AUTO` 表示由 Prompt AI 自主规划，不是固定镜头或具体景别。未确认来源的历史值保持原样存储但默认不锁定。
- `seed` 支持手动指定视频随机种子，范围为 `1` 到 `9007199254740991`，可传十进制字符串；省略或为 `null` 时自动生成。
- **指定位置插入**：创建时可传 `insertBeforeShotId` 或 `insertAfterShotId`（只允许一个，Snowflake ID 可传字符串）。新镜头继承锚点所在的镜头组和场次归属；若指定 `shotGroupId`，必须与锚点一致。后端按 `(sortOrder ASC, shotNo ASC, id ASC)` 定位锚点，在同一事务内插入并将该组全部镜头的 `sort_order` 和 `shot_no` 连续重排为 `1..N`，已有镜头名称及其他配置保留。组内插入不重排其他镜头组。锚点不存在返回 404，场次/组不一致返回 400，等待排序锁期间锚点已移走返回 409。创建时传入的 `shotNo` / `sortOrder` 由插入位置决定。
- 前端在分镜卡片“更多”菜单提供“在此镜头前插入分镜”和“在此镜头后插入分镜”，打开新建配置，保存后生效。取消新建不会创建镜头或改变排序；插入配置的镜头组和序号由位置决定。

### 4.4 更新分镜
- **URL**: `PUT /drama/shot`
- **Body**: `DramaShotDTO`
- `seed` 提交有效整数时更新视频随机种子；省略或为 `null` 时保留已有值。分镜配置只在种子被手动修改时提交该字段，避免旧表单覆盖已刷新的种子；已排队任务继续使用提交时冻结的种子。
- **背景字段更新**: `resSceneId`、`resKeyframeId`、`customScenePrompt` 省略时保留已有值，显式 `null` 表示主动清空。场景资产与关键帧资产互斥：提交有效 `resSceneId` 自动清空关键帧；提交有效 `resKeyframeId` 自动清空场景。自定义背景描述独立保存，选择关键图不会清除该描述。完整编辑表单需显式提交被清空的背景字段；仅保存提示词、渲染设置等内容时可以省略它们。

### 4.5 删除分镜
- **URL**: `DELETE /drama/shot/{id}`

### 4.6 克隆/复制分镜
- **URL**: `POST /drama/shot/{id}/clone`
- **返回**: `R<Long>` (新克隆的分镜 ID)

### 4.7 批量调整分镜排序 / 跨场次移动
- **URL**: `PUT /drama/shot/reorder`
- **Body**: `DramaShotReorderDTO`
```json
{
  "sceneId": 1001,
  "shotIds": [10001, 10003, 10002]
}
```
- **返回**: `R<Void>`

### 4.8 单镜一键组装 Prompt
- **URL**: `POST /drama/shot/{id}/assemble-prompt`
- **返回**: `R<PromptAssembleResultVO>` (组装结果并自动同步更新至该分镜)

### 4.9 批量组装 Prompt
- **URL**: `POST /drama/shot/batch-assemble`
- **Body**: `DramaShotBatchAssembleDTO`

### 4.9.1 首尾帧生图

- **URL**: `POST /drama/shot/{id}/generate-first-frame`
- **Body**: `DramaShotFirstFrameDTO`

首尾帧生图请求的 `frameType` 统一使用 `FIRST_FRAME`（首帧，省略时默认）或
`LAST_FRAME`（尾帧）。显式传入其他值返回参数错误；尾帧结果仍保存到
`endFrameImageUrl`。分镜参考图 `sourceType=KEYFRAME` 时，`usageRole` 只允许
七种关键图用途，未填写时继承关联关键图资产用途。

人物参考图（`sourceType=CHARACTER/CHARACTER_REFERENCE`，也支持自行上传的人物图）可按当前分镜切换 `usageRole`：

- `SUBJECT`：作为主体人物参考。
- `NON_SUBJECT`：作为第一人称视角人物或画外人物的设定参考。保留图片槽位、人物/造型关联和上传顺序，不因参考图的存在安排人物出镜，也不为画外人物建立可见人物 `<Subject N>`。只有剧本明确可见的手、衣袖等局部才使用对应造型；画外对白保留原说话人及音频绑定。

`referenceRole=IDENTITY/LOOK/COMBINED` 独立描述图片约束的是身份还是造型，不随主体切换而清空。`NON_SUBJECT` 是本系统显式用途，不是 H3 官方标签；它不会自行把镜头切换为第一人称，剧本或创作者指令仍应明确视角。前端在参考图槽位的“用途”下拉中切换，选择随 `refImages` 保存；切换后需重新生成提示词，已生成结果的素材指纹会失效。无需数据库迁移。

### 4.10 生成 AI 首帧与运镜方案

- **URL**: `POST /drama/shot/{id}/ai-visual-plan`
- **Body**: `ShotAiVisualPlanRequestDTO` (可选 `providerId`, `modelCode`, `instruction`)
- **说明**: 服务端根据当前分镜绑定的人物、场景、道具和镜头参数构建事实上下文，由 AI 返回首帧 Prompt、负向 Prompt、视频运镜 Prompt 和参考图。只预览，不修改分镜。
- **返回**: `R<ShotAiVisualPlanVO>`

### 4.11 应用 AI 首帧与运镜方案
- **URL**: `POST /drama/shot/{id}/ai-visual-plan/apply`
- **Body**: `ShotAiVisualPlanApplyDTO`
- **说明**: 将用户确认后的三个 Prompt 写入 `drama_shot.prompt`、`negative_prompt`、`video_prompt`。
- **返回**: `R<ShotAiVisualPlanVO>`

### 4.12 单镜提交 ComfyUI 渲染
- **URL**: `POST /drama/shot/{id}/render`
- **Body**: `DramaShotRenderRequestDTO` (可选自定义参数: providerId, workflowTemplateId, seed)
- **返回**: `R<ComfyRenderTaskVO>`

### 4.13 上一镜视频尾帧按需提取与首帧引用 (On-demand Previous Video Tail Inheritance)
- **URL**: `POST /drama/shot/{currentShotId}/inherit-previous-video-tail`
- **Body**: `PreviousVideoTailRequest` (可选: `forceExtract: boolean`)
- **说明**:
  - 在同一连续镜头组（`shotGroupId`）内，根据 `(sortOrder ASC, shotNo ASC, id ASC)` 定位当前分镜的直接前驱分镜；
  - 若前驱分镜无生成视频（`videoUrl`），直接抛出 `400: 上一镜尚未生成视频`；
  - 若前驱分镜已缓存 `lastFrameUrl`，来源视频 Take ID（无 Take 时按视频 URL）及抽帧策略版本均一致，且未指定 `forceExtract=true`，则直接复用；
  - 否则通过 ffmpeg 解码视频并保留实际最后一帧（无时间偏移），以 JPEG 上传至 MinIO，并更新前驱分镜的 `lastFrameUrl` 与 `lastFrameSourceVideoUrl` 缓存；
  - 将提取后的尾帧 URL 回写至当前分镜的 `previewImageUrl`，并将 `firstFrameSourceType` 置为 `PREVIOUS_VIDEO_TAIL`，`firstFrameSourceShotId` 指向前驱分镜 ID，`firstFrameSourceVideoUrl` 指向来源视频 URL。
- **返回**: `R<PreviousVideoTailVO>`
```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "currentShotId": "2032095628944588802",
    "sourceShotId": "2032095628944588801",
    "sourceShotNo": 1,
    "sourceShotName": "S01-01",
    "sourceVideoUrl": "http://127.0.0.1:9000/freyja/videos/shot_1.mp4",
    "tailFrameUrl": "http://127.0.0.1:9000/freyja/projects/1/shots/1/frames/video_last_decoded_v1_123.jpg",
    "reused": true,
    "applied": true
  }
}
```

尾帧缓存同时校验抽取策略版本：旧的偏移帧缓存不复用，下一次引用时自动重新提取实际末帧。已经保存为当前分镜首帧的图片保留，重新点击引用后使用新末帧。

**Ref2VA 新建场景**：`POST /drama/shot/{currentShotId}/extract-previous-video-tail` 使用相同请求与前驱镜头校验、缓存机制，返回相同结构且 `applied: false`。它只提取并归档尾帧，不修改当前镜头首帧。前端在参考图模式的“镜头环境场景”区域提供“用上一镜视频尾帧新建场景”入口：先保存当前分镜，再提取尾帧并跳转场景新建页，将 `tailFrameUrl` 预填到场景的 `referenceImageUrl`。用户填写其余场景资料并保存后，沿用新建场景流程返回分镜并绑定场景。首尾帧模式继续使用上述首帧引用接口。

### 4.13.1 下一镜视频首帧引用为当前尾帧（FL2VA）

- **URL**：`POST /drama/shot/{currentShotId}/inherit-next-video-head`
- **Body**：可选 `NextVideoHeadRequest`，如 `{ "forceExtract": true }`。
- 仅支持 `FIRST_LAST_FRAME` 模式。在同一连续镜头组中按 `(sortOrder ASC, shotNo ASC, id ASC)` 选择直接后一镜，不跨组、不跳过尚无视频的镜头；没有后一镜或其 `videoUrl` 为空返回 400。
- 用 FFmpeg 解码后一镜已生成视频的实际第一帧（无时间偏移，非配置中的 `previewImageUrl`），以 JPEG 归档到 MinIO，并仅回写当前镜头的 `endFrameImageUrl`。来源镜头配置、当前首帧及视频产物不变。
- 缓存存放在来源镜头的 `frames/video_first_decoded_v1_{hash}.jpg`，hash 由来源视频 URL 和 Take ID 确定；视频版本变化自动使用新缓存键，无需新增数据库字段。`forceExtract=true` 会绕过缓存重新提取；返回 URL 包含缓存版本参数。
- 抽帧后复核当前组、模式、直接后一镜和来源视频版本；发生变化返回 409，不应用提取结果。归档/抽帧失败返回 500，保留当前尾帧。
- **返回**：`R<NextVideoHeadVO>`，包含 `currentShotId`、`sourceShotId`、`sourceShotNo`、`sourceShotName`、`sourceVideoUrl`、`sourceVideoTakeId`、`headFrameUrl`、`reused`、`applied: true`。引用是当次视频版本的图片快照；后一镜重新生成或重选 Take 后，可再次点击引用更新。
- 分镜配置的尾帧卡片标题栏提供“引用下一镜首帧”链接，样式与首帧卡片的“引用上一镜尾帧”一致。首尾帧渲染工坊在对应卡片标题栏提供提取引用按钮，并在已引用视频首帧时提供“重新提取”入口；需先保存当前分镜并生成后一镜视频。

组内拖拽排序与插入共用镜头组事务锁，拖拽请求必须包含该组全部镜头且 ID 不重复；若页面列表未包含刚插入的镜头，返回 409 提示刷新，避免局部重排导致排序重复。

### 4.14 分页查询分镜视频抽卡候选历史 (Shot Video Takes)
- **URL**: `GET /drama/shot/{shotId}/video-takes`
- **Query**: `current` (int, 默认1), `size` (int, 默认12, 最大50), `status` (string, 默认 AVAILABLE)
- **说明**: 检索指定分镜下所有生成成功的历史视频候选版本，按生成时间与候选编号倒序排列。根据当前分镜绑定的 `currentVideoTakeId` 动态标记 `current: true/false`。
- **返回**: `R<Page<ShotVideoTakeVO>>`
```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "records": [
      {
        "id": "2032095628944588801",
        "shotId": "2032095628944588700",
        "takeNo": 2,
        "taskId": "RENDER_1789912000000",
        "sourceType": "AI_GENERATED",
        "status": "AVAILABLE",
        "videoUrl": "http://127.0.0.1:9000/freyja/videos/shot_1001_take2.mp4",
        "current": true,
        "providerId": "2032000000000000001",
        "providerName": "Local FastAPI",
        "modelCode": "MiniMax-H3",
        "generationMode": "FIRST_LAST_FRAME",
        "seed": 123456,
        "size": "720x1280",
        "duration": 5.0,
        "promptSnapshot": "Camera pushes in slowly",
        "negativePromptSnapshot": "blurry, low quality",
        "firstFrameUrl": "http://127.0.0.1:9000/freyja/first.jpg",
        "createTime": "2026-09-20T21:00:00"
      }
    ],
    "total": 2,
    "current": 1,
    "size": 12,
    "pages": 1
  }
}
```

### 4.15 选择历史候选版本作为当前视频 (Select Video Take)
- **URL**: `POST /drama/shot/{shotId}/video-takes/{takeId}/select`
- **说明**: 将指定的候选版本切换为分镜的当前视频版本。在短事务中原子更新 `drama_shot.current_video_take_id` 与 `video_url`，并协同清除旧当前视频派生的尾帧缓存（`last_frame_url` / `last_frame_source_take_id`）。不删除文件、不复制文件，重复调用幂等返回 `changed: false`。
- **返回**: `R<ShotVideoTakeSelectVO>`
```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "shotId": "2032095628944588700",
    "previousTakeId": "2032095628944588799",
    "currentTakeId": "2032095628944588801",
    "videoUrl": "http://127.0.0.1:9000/freyja/videos/shot_1001_take2.mp4",
    "changed": true
  }
}
```

### 4.16 删除视频候选版本 (Delete Video Take)
- **URL**: `DELETE /drama/shot/{shotId}/video-takes/{takeId}`
- **说明**: 删除候选记录及其所属 MinIO 桶中的视频对象。仅允许删除本系统 MinIO 视频；若文件仍被其他分镜、候选版本或正在处理的任务引用，返回 409。删除当前版本会清空分镜的 `current_video_take_id`、`video_url` 和尾帧缓存。关联的视频后处理任务保留历史记录，但清空已删除产物的地址。
- **返回**: `R<Void>`。
