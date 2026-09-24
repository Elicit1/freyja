# 视频后处理调度与分镜源管理接口文档 (video-processing-api)

> 供视频超分增强 (`VideoUpscale`)、视频平滑补帧 (`FrameInterpolation`) 工作台及外部系统调用。基路径为 `/video-processing`。
> 所有接口统一返回 `R<T>` 包装结构。

---

## 1. 核心架构设计与原则

1. **结构化来源选择 (Source Types)**：
   - `SHOT_CURRENT`：直接选取短剧分镜当前生效视频（`drama_shot.video_url`），后端自动追溯当前 Take ID。
   - `SHOT_VIDEO_TAKE`：选取指定分镜抽卡历史版本（`drama_shot_video_take`）。
   - `DIRECT_URL`：本地上传至 MinIO 或受白名单信任的外部 URL。
2. **不可变 URL 快照原则**：
   - 任务入队前由 `VideoProcessSourceResolver` 解析并固化 `source_video_url` 快照。后续异步任务仅消费该快照，分镜或 Take 的后续重滚与切换不影响已提交任务。
3. **模型中心动态驱动 (Zero Hardcoded Models)**：
   - 任务必须提交 `providerId + modelId`，不再接受前端硬编码模型路径与参数。
   - 模型参数范围与底层引擎文件名通过 `AiModel.paramsJson.videoProcessing` 安全控制，由 `VideoProcessingModelResolver` 负责清洗与映射。
4. **派生资产解耦**：
   - 后处理任务输出不自动覆盖 `drama_shot.video_url`，杜绝非预期状态修改与尾帧缓存失效。
5. **Snowflake ID 精度保护**：
   - 前后端所有实体主键与外键关联 ID 必须保持为 `String`，严禁在前端执行数字转换。

---

## 2. 接口列表

### 2.1 查询可选分镜视频列表

- **接口路径**：`GET /video-processing/source-shots`
- **功能描述**：用于分镜视频选择弹窗（`ShotVideoPickerDialog`），按短剧、剧集、场次及关键词分页检索已生成视频的分镜。
- **请求参数 (Query)**：

| 参数名 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `dramaId` | String | 否 | 短剧 ID |
| `episodeId` | String | 否 | 剧集 ID |
| `sceneId` | String | 否 | 场次 ID |
| `keyword` | String | 否 | 匹配镜头名称或镜号 |
| `current` | Integer | 否 | 页码，默认 1 |
| `size` | Integer | 否 | 每页条数，默认 20，上限 50 |

- **响应数据 (`data`)**：

```json
{
  "total": 12,
  "size": 20,
  "current": 1,
  "pages": 1,
  "records": [
    {
      "dramaId": "2032095628944588800",
      "dramaTitle": "仙途奇缘",
      "episodeId": "2032095628944588801",
      "episodeNo": 1,
      "episodeTitle": "初入宗门",
      "sceneId": "2032095628944588802",
      "sceneNo": 1,
      "sceneName": "山门前",
      "shotId": "2032095628944588803",
      "shotNo": 1,
      "shotName": "林萧御剑飞行落地",
      "duration": 5.0,
      "generationMode": "FIRST_LAST_FRAME",
      "videoUrl": "http://127.0.0.1:9000/video-assets/shot_01.mp4",
      "posterUrl": "http://127.0.0.1:9000/video-assets/shot_01_first.jpg",
      "currentVideoTakeId": "2032095628944588805",
      "videoTakeCount": 3,
      "videoUpdatedTime": "2026-09-20 18:00:00"
    }
  ]
}
```

---

### 2.2 获取后处理可用模型与参数范围

- **接口路径**：`GET /video-processing/models`
- **功能描述**：根据选中的 AI 提供商和操作类型，返回已启用且符合安全配置规范的模型选项。
- **请求参数 (Query)**：

| 参数名 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `providerId` | String | 是 | AI 提供商 ID |
| `operation` | String | 是 | `VIDEO_UPSCALE` 或 `FRAME_INTERPOLATION` |

- **响应数据 (`data`)**：

```json
[
  {
    "id": "2032000000000000101",
    "providerId": "2032000000000000001",
    "modelCode": "realesrgan-x2-video",
    "modelName": "RealESRGAN 2X 高清超分",
    "modelType": "VIDEO_UPSCALE",
    "sortOrder": 1,
    "config": {
      "defaultScale": 2,
      "allowedScales": [2, 4],
      "defaultCrf": 16,
      "minCrf": 10,
      "maxCrf": 30,
      "defaultPreserveAudio": true
    }
  }
]
```

---

### 2.3 探测结构化来源视频元信息

- **接口路径**：`POST /video-processing/probe-source`
- **功能描述**：通过统一的 `VideoProcessSourceResolver` 校验来源合法性与安全性，解析并返回视频分辨率、FPS、时长及音频轨道信息。
- **请求体 (JSON)**：

```json
{
  "sourceType": "SHOT_CURRENT",
  "sourceShotId": "2032095628944588803"
}
```

- **响应数据 (`data`)**：

```json
{
  "width": 1280,
  "height": 720,
  "fps": 24.0,
  "duration": 5.0,
  "videoCodec": "h264",
  "audioCodec": "aac",
  "hasAudio": true,
  "sourceUrl": "http://127.0.0.1:9000/video-assets/shot_01.mp4"
}
```

---

### 2.4 提交后处理任务

- **接口路径**：`POST /video-processing/tasks`
- **功能描述**：提交超分辨率增强或平滑补帧任务。
- **请求体 (JSON)**：

```json
{
  "operation": "VIDEO_UPSCALE",
  "sourceType": "SHOT_CURRENT",
  "sourceShotId": "2032095628944588803",
  "providerId": "2032000000000000001",
  "modelId": "2032000000000000101",
  "sourceFps": 24,
  "targetFps": 24,
  "scale": 2,
  "crf": 16,
  "preserveAudio": true
}
```

- **响应数据 (`data`)**：

```json
{
  "id": "2032095628944589999",
  "taskId": "task-vpu-20260920-001",
  "operation": "VIDEO_UPSCALE",
  "sourceType": "SHOT_CURRENT",
  "sourceShotId": "2032095628944588803",
  "sourceShotNo": 1,
  "sourceShotName": "林萧御剑飞行落地",
  "sourceDramaTitle": "仙途奇缘",
  "sourceEpisodeTitle": "初入宗门",
  "sourceSceneName": "山门前",
  "sourceVideoUrl": "http://127.0.0.1:9000/video-assets/shot_01.mp4",
  "providerId": "2032000000000000001",
  "modelId": "2032000000000000101",
  "modelCode": "realesrgan-x2-video",
  "status": "QUEUED",
  "progress": 0,
  "submitTime": "2026-09-20 23:00:00"
}
```

---

### 2.5 查询任务状态与详情

- **接口路径**：`GET /video-processing/tasks/{taskId}`
- **功能描述**：用于前端轮询执行进度与节点。

---

### 2.6 分页查询历史任务

- **接口路径**：`GET /video-processing/tasks/page`
- **功能描述**：用于超分与补帧工作台侧边抽屉展示历史记录，已包含来源短剧、分镜与 Take 标注。

---

### 2.7 取消任务

- **接口路径**：`POST /video-processing/tasks/{taskId}/cancel`
- **功能描述**：取消正在排队或执行中的后处理任务。
