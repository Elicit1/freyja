# ComfyUI Universal Multi-Workflow OpenAI Gateway
> 生产级通用多工作流 API 网关微服务 · 部署于 ComfyUI 前端 · 完全兼容 OpenAI SDK 图像生成规范

---

## 🌟 核心特性

1. **配置驱动设计（Config-Driven）**：
   - 采用 `workflows.yaml` + `workflows/*.json` 解耦架构。
   - 新增、修改或切换模型工作流（如 FLUX.2 Klein 9B、SDXL、动漫二次元模型等）**无需修改任何 Python 核心代码**，只需在 YAML 中配置 `mappings`。
2. **OpenAI SDK 协议完全兼容**：
   - 路由：`POST /v1/images/generations`，支持标准入参：`model`、`prompt`、`negative_prompt`、`size`、`steps`、`seed`、`response_format` ("url" / "b64_json")。
   - 尺寸：图片工作流宽高必须为 16 的整数倍，MiniMax H3 视频工作流宽高必须为 32 的整数倍。非法尺寸返回 `400 invalid_parameters`，网关不会静默缩放。
   - 拓展参数支持：通过 `extra_body` 注入 `ref_images: List[str]`（支持 HTTP/HTTPS 网络图片、本地文件路径及 Base64 Data URI）。
   - 严格对齐 OpenAI 标准响应格式 `{ "created": 1717..., "data": [{"url": "...", "b64_json": null}] }`。
3. **FLUX.2 Klein 9B 动态参考图插拔与链式装配（`flux2_reference_chain`）**：
   - **0 张参考图**：纯文生图模式，保持正向提示词与引导直连 KSampler，不产生冗余图像加载节点。
   - **1~N 张参考图**：自动将网络 URL/本地文件/Base64 转存上传至 ComfyUI `/upload/image`，并动态在图中追加 `LoadImage -> VAEEncode -> ReferenceLatent` 节点链，将多图特征依次串联后接入采样器。
4. **高并发异步通信与双重容灾**：
   - 基于 `httpx.AsyncClient` 连接池提交任务。
   - 基于 `websockets` 独立追踪 `clientId` 的 `progress`、`executing` 与 `executed` 事件。
   - 包含 120s 超时与 `GET /history/{prompt_id}` 轮询降级双重保障机制。
5. **本地静态文件托管**：
   - 生成的图片自动下载保存在 `static/outputs/`，并通过 `/outputs/{filename}` 路由直接对外提供高速访问 URL。

---

## 📁 目录结构

```
comfy_gateway/
├── config.py                 # Pydantic 配置文件加载与环境变量解析
├── workflows.yaml            # 模型注册与节点属性映射配置 (Config-Driven 核心)
├── schemas.py                # OpenAI 请求体校验与响应体规范模型
├── workflow_engine.py        # 通用参数注入引擎与 FLUX.2 多参考图链式拼装器
├── comfy_client.py           # 异步 HTTP 客户端、WebSocket 进度监听与容灾轮询
├── main.py                   # FastAPI 应用主路由、CORS、静态文件挂载与生命周期管理
├── client_test.py            # 使用官方 openai Python SDK 的全功能调用测试
├── test_offline.py           # 离线单元测试 (测试节点组装、属性注入与动态参考图链)
├── test_api.py               # API 路由与输入校验单元测试
├── requirements.txt          # Python 依赖清单
├── start.ps1                 # Windows PowerShell 一键启动脚本
├── start.sh                  # Linux / macOS 一键启动脚本
├── workflows/
│   ├── flux2_klein.json      # FLUX.2 Klein 9B ComfyUI 工作流模板
│   └── sdxl_base.json        # SDXL 1.0 Base ComfyUI 工作流模板
└── static/
    └── outputs/              # 本地保存的生成产物图片目录
```

---

## 🚀 快速启动

### 1. 安装依赖
```bash
cd comfy_gateway
pip install -r requirements.txt
```

### 2. 启动 ComfyUI 网关服务
- **Windows (PowerShell)**:
  ```powershell
  .\start.ps1
  ```
- **或者直接使用 Python 启动**:
  ```bash
  python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
  ```

服务启动后：
- 网关地址：`http://127.0.0.1:8000`
- Swagger API 文档：`http://127.0.0.1:8000/docs`
- 挂载输出路径：`http://127.0.0.1:8000/outputs/`

---

## ⚙️ 配置文件解析 (`workflows.yaml`)

```yaml
server:
  host: "0.0.0.0"
  port: 8000
  comfyui_http_url: "http://127.0.0.1:8188"
  comfyui_ws_url: "ws://127.0.0.1:8188/ws"
  timeout_seconds: 120
  output_dir: "static/outputs"
  base_url: "http://127.0.0.1:8000"

models:
  - model_name: "flux-2-klein-9b"
    description: "FLUX.2 Klein 9B 高速图像生成与多参考图编辑模型"
    template_file: "workflows/flux2_klein.json"
    handler: "flux2_reference_chain"
    default_steps: 4
    default_cfg: 1.0
    mappings:
      prompt: ["3", "inputs", "text"]
      negative_prompt: ["5", "inputs", "text"]
      guidance: ["4", "inputs", "guidance"]
      seed: ["7", "inputs", "seed"]
      steps: ["7", "inputs", "steps"]
      cfg: ["7", "inputs", "cfg"]
      width: ["6", "inputs", "width"]
      height: ["6", "inputs", "height"]
    handler_config:
      vae_node_id: "8"
      sampler_node_id: "7"
      sampler_cond_param: "positive"
      base_conditioning_source: ["4", 0]
      reference_node_type: "ReferenceLatent"
```

---

## 💻 客户端调用示例

### 1. 官方 OpenAI Python SDK 调用示例

```python
from openai import OpenAI

# 初始化客户端，base_url 指向网关 /v1
client = OpenAI(
    base_url="http://127.0.0.1:8000/v1",
    api_key="not-needed"
)

# 案例 A：纯文生图 (0 张参考图)
response = client.images.generate(
    model="flux-2-klein-9b",
    prompt="A futuristic cyberpunk detective in neon rain, photorealistic, 8k",
    size="1280x720",
    response_format="url"
)
print("生成的图片 URL:", response.data[0].url)

# 案例 B：多图参考特征注入 (通过 extra_body.ref_images 传入)
response = client.images.generate(
    model="flux-2-klein-9b",
    prompt="The same character sitting in a café reading a holographic paper",
    size="1024x1024",
    response_format="url",
    extra_body={
        "ref_images": [
            "https://example.com/character_face.png",
            "F:/assets/props/cyber_coat.png"
        ],
        "steps": 4,
        "seed": 42
    }
)
print("多图参考生成图片 URL:", response.data[0].url)

# 案例 C：Base64 响应格式
response_b64 = client.images.generate(
    model="flux-2-klein-9b",
    prompt="A glowing geometric crystal floating in zero gravity",
    size="512x512",
    response_format="b64_json"
)
print("Base64 字符长度:", len(response_b64.data[0].b64_json))
```

# 案例 D：MiniMax H3 FL2VA 首尾帧 / 首帧生视频与原生音频 (15s @ 24fps = 362 帧)
response_video = client.images.generate(
    model="minimax-h3-fl2va",
    prompt="A cinematic camera pan through ancient ruins as lightning strikes and thunder rumbles",
    size="16:9",
    response_format="url",
    extra_body={
        "duration": 15.0,
        "ref_images": [
            "https://example.com/opening_shot.png",
            "https://example.com/ending_shot.png"
        ]
    }
)
print("生成的视频+音频 URL (mp4):", response_video.data[0].url)

# 案例 E：MiniMax H3 Ref2VA 多参考图连贯视频生成
response_ref_video = client.images.generate(
    model="minimax-h3-ref2va",
    prompt="A hero in mecha dragon armor walking through misty mountains, cinematic lighting",
    size="16:9",
    response_format="url",
    extra_body={
        "duration": 15.0,
        "ref_images": [
            "https://example.com/character_concept.png",
            "https://example.com/weapon_prop.png"
        ]
    }
)
print("多参考图生成的视频 URL:", response_ref_video.data[0].url)
```

### 2. cURL 调用示例

```bash
curl -X POST http://127.0.0.1:8000/v1/images/generations \
  -H "Content-Type: application/json" \
  -d '{
    "model": "minimax-h3-fl2va",
    "prompt": "Cinematic sci-fi scene with synchronized audio ambience",
    "size": "16:9",
    "extra_body": {
      "duration": 15.0,
      "ref_images": ["https://example.com/first_frame.png"]
    }
  }'
```

---

## 🧪 验证与测试

### 长任务断线恢复

节点 YAML 支持 `ws_ping_interval: 30`、`ws_ping_timeout: 120`、
`recovery_check_interval: 15` 和 `transfer_timeout_seconds: 900`（单位均为秒）。
执行预算仍由模型或服务的 `timeout_seconds` 决定；重连沿用原 promptId 和剩余预算，
不会重新提交工作流。WebSocket 连接失败时退避重连，并查询完成历史兜底。
仅结构化终态能确认成功或失败；不可达、记录缺失和总时限耗尽均视为结果待核实。

任务与算力节点的绑定在 Redis 保留七天，网关进程内映射同样按期限失效。
`GET /v1/tasks/{taskId}` 查询真实状态，成功时提供 `/v1/tasks/{taskId}/output` 下载入口；
下载使用完成历史中的视频产物并做原子缓存，不调用生成接口。
Redis 不可用时降级为内存映射，该情况下进程重启不能恢复映射。

批次抽屉的“查询并取回结果”调用后端
`POST /drama/video-render-batches/{batchId}/recover-result`，请求体为 `{"taskId":"RENDER_…"}`。
确认完成后只排队归档，使用原 taskId 幂等保存 Take，成功后继续剩余分镜。
取消的批次不能恢复；未知状态继续保留全局队列占用。
旧版本已经删除映射的任务，需要运维依据已验证的 taskId/promptId/nodeId 恢复绑定，
不能通过猜测分镜或解析报错文本关联上游产物。

后端视频 HTTP 等待默认至少 3600 秒，可由 `freyja.video-http-timeout-seconds` 调整；
视频传输默认 900 秒，可由 `freyja.video-transfer-timeout-seconds` 调整。
部署时应让 HTTP 等待预算覆盖上游执行时间、输入上传与结果传输时间。

- **运行全量离线逻辑测试**:
  ```bash
  python test_offline.py
  ```
- **运行 API 端点测试**:
  ```bash
  python test_api.py
  ```
- **运行真实/连通性端到端测试**:
  ```bash
  python client_test.py
  ```
