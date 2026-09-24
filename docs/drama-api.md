# 短剧-剧集-情景-分镜 API 规范文档 (drama-api)

> 基础路径：`http://127.0.0.1:8080`
> 统一返回结构：`R<T>` (`code`: 200, `msg`: "success", `data`: T)

---

## 1. 短剧管理接口 (`DramaController` / `/drama`)

### 1.1 分页查询短剧列表
- **URL**: `GET /drama/page`
- **Query**: `current` (int), `size` (int), `title` (string), `genre` (string), `status` (string)
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

### 4.4 更新分镜
- **URL**: `PUT /drama/shot`
- **Body**: `DramaShotDTO`

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
- **Body**: `PreviousVideoTailRequest` (可选: `forceExtract: boolean`, `tailOffsetMs: Integer`)
- **说明**:
  - 在同一连续镜头组（`shotGroupId`）内，根据 `(sortOrder ASC, shotNo ASC, id ASC)` 定位当前分镜的直接前驱分镜；
  - 若前驱分镜无生成视频（`videoUrl`），直接抛出 `400: 上一镜尚未生成视频`；
  - 若前驱分镜已缓存 `lastFrameUrl` 且其 `lastFrameSourceVideoUrl` 与当前 `videoUrl` 一致，且未指定 `forceExtract=true`，则直接复用；
  - 否则通过 ffmpeg 截取尾帧（倒数 `tailOffsetMs` 毫秒，默认 300ms），无损上传至 MinIO，并更新前驱分镜的 `lastFrameUrl` 与 `lastFrameSourceVideoUrl` 缓存；
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
    "tailFrameUrl": "http://127.0.0.1:9000/freyja/frames/shot_1_tail.jpg",
    "reused": true,
    "applied": true
  }
}
```

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
