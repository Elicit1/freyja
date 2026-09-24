# ComfyUI 私有化算力接入与调度接口规范

本文档定义了 ComfyUI 私有化算力接入、工作流参数替换、任务异步调度、WebSocket 实时进度监听以及 MinIO 自动归档相关的 RESTful 接口规范。

---

## 1. 接口概览

| 端点 | Method | 描述 |
| :--- | :--- | :--- |
| `/api/v1/render/comfy/submit` | `POST` | 提交渲染任务（参数插桩、异步下发 ComfyUI、监听进度） |
| `/api/v1/render/comfy/task/{taskId}` | `GET` | 根据业务 Task ID 查询渲染实时状态与产物 URL |
| `/api/v1/render/comfy/task/by-prompt/{promptId}` | `GET` | 根据 ComfyUI Prompt ID 查询任务状态 |
| `/api/v1/render/comfy/templates` | `GET` | 获取系统预设工作流模板列表 (SDXL/Wan2.1) |
| `/api/v1/render/comfy/upload-ref` | `POST` | 上传参考图/媒体素材至 ComfyUI input 目录 |

---

## 2. 接口详细说明

### 2.1 提交 ComfyUI 渲染生成任务
- **URL**: `/api/v1/render/comfy/submit`
- **Method**: `POST`
- **Content-Type**: `application/json`

#### 请求参数 (ComfyRenderSubmitDTO)
```json
{
  "providerId": 1,
  "workflowTemplateId": "SDXL_TXT2IMG",
  "workflowJson": null,
  "prompt": "1girl, solo, dynamic angle, anime style, high detail",
  "negativePrompt": "low quality, blurry, text, watermark",
  "videoPrompt": "镜头缓慢推近。她转身望向镜头，衣袂飘动。",
  "firstFrameImage": "firstframe_1756282500000.png",
  "lastFrameImage": null,
  "seed": -1,
  "steps": 20,
  "referenceImage": "ref_character.png",
  "projectId": 1001,
  "shotId": 2005,
  "customParams": {
    "3.cfg": 7.5,
    "PROMPT_INPUT.text": "custom override"
  }
}
```

#### 工作流节点标题约定
- `PROMPT_INPUT`（或 `VIDEO_PROMPT_INPUT`）：正向提示词 ← `prompt` / `videoPrompt`
- `NEGATIVE_PROMPT`：负向提示词 ← `negativePrompt`
- `FIRST_FRAME_INPUT`：视频首帧图 ← `firstFrameImage`（ComfyUI input 目录文件名）
- `LAST_FRAME_INPUT`：视频末帧图（可选）← `lastFrameImage`
- `IMAGE_INPUT`：图生图参考图 ← `referenceImage`
- `KSAMPLER`：种子 / 步数 ← `seed` / `steps`

#### 响应结构 (R<ComfyRenderTaskVO>)
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "taskId": "7a3562a1-0e12-4cfb-81d3-6e47f2b96312",
    "promptId": "a98db257-238d-4cb0-9bc8-243e88dc6341",
    "status": "RUNNING",
    "statusDesc": "执行中",
    "progress": 0,
    "currentNode": null,
    "outputUrl": null,
    "outputFilename": null,
    "errorMessage": null,
    "createTime": "2026-08-27T15:15:00",
    "finishTime": null,
    "executionTimeMs": null
  }
}
```

---

### 2.2 查询任务实时状态与进度
- **URL**: `/api/v1/render/comfy/task/{taskId}`
- **Method**: `GET`

#### 响应结构（完成状态示例）
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "taskId": "7a3562a1-0e12-4cfb-81d3-6e47f2b96312",
    "promptId": "a98db257-238d-4cb0-9bc8-243e88dc6341",
    "status": "SUCCESS",
    "statusDesc": "完成",
    "progress": 100,
    "currentNode": null,
    "outputUrl": "http://127.0.0.1:9000/video-assets/projects/1001/shots/2005/takes/take_1756282500000.png",
    "lastFrameUrl": "http://127.0.0.1:9000/video-assets/projects/1001/shots/2005/takes/take_1756282500000_last.jpg",
    "outputFilename": "Freyja_Output_0001_.png",
    "errorMessage": null,
    "createTime": "2026-08-27T15:15:00",
    "finishTime": "2026-08-27T15:15:08",
    "executionTimeMs": 8120
  }
}
```

---

### 2.3 获取预设工作流模板
- **URL**: `/api/v1/render/comfy/templates`
- **Method**: `GET`

#### 响应结构
```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "templateId": "SDXL_TXT2IMG",
      "templateName": "SDXL 高清文生图工作流",
      "templateType": "IMAGE",
      "description": "标准 SDXL 双文本编码器文生图，支持正反提示词、随机种子、采样步数控制",
      "supportedParams": ["prompt", "negativePrompt", "seed", "steps"],
      "defaultWorkflowJson": "{...}"
    },
    {
      "templateId": "SDXL_IMG2IMG",
      "templateName": "SDXL 分镜图生图/重绘工作流",
      "templateType": "IMAGE",
      "description": "基于参考图生成分镜画面，支持参考图输入、提示词与降噪重绘幅度调节",
      "supportedParams": ["prompt", "negativePrompt", "referenceImage", "seed", "steps"],
      "defaultWorkflowJson": "{...}"
    },
    {
      "templateId": "WAN_VIDEO_GEN",
      "templateName": "Wan2.1 / SVD 分镜视频生成工作流",
      "templateType": "VIDEO",
      "description": "基于首帧参考图与提示词生成动态分镜视频切片 (MP4)",
      "supportedParams": ["prompt", "referenceImage", "seed", "steps"],
      "defaultWorkflowJson": "{...}"
    }
  ]
}
```

---

### 2.4 上传参考图至 ComfyUI
- **URL**: `/api/v1/render/comfy/upload-ref`
- **Method**: `POST`
- **Content-Type**: `multipart/form-data`
- **表单字段**:
  - `file`: 二进制文件
  - `providerId`: 提供商 ID (可选)
  - `subfolder`: 子目录 (可选)
  - `overwrite`: 是否覆盖 (true/false)
