# SIF Server

SIF Server 为 AI / Agent 与 SIF Client 提供 REST 和 MCP 生图服务。工作流、App 注册、认证、SQLite 任务记录和 Docker 数据卷均位于这个模块。运行在本机 Ubuntu WSL 的独立容器 `sif`，访问地址为 `http://127.0.0.1:8011`。GPU 生图继续调用已配置的 ComfyUI。

服务端名称为 SIF Server，Skill 名称为 `sif-image-generation`，提供 `pro`、`standard`、`fast` 三个档位。Server 提供 HTML 管理页、Streamable HTTP MCP 与 REST API。Client 通过 REST 访问 Server，安装与配置见 [SIF Client](../sif_client/README.md)。

## 登录与 API Keys

1. 启动后打开 [SIF 控制台](http://127.0.0.1:8011/)。未设置自定义密码时，SIF 自动生成初始管理员密码，在当前启动日志中显示：`wsl -d Ubuntu --exec docker logs sif`。设置自己的密码前，每次重启都会生成新的初始密码。
2. 使用密码登录，在「安全设置」设置自己的密码。自定义密码的加盐 scrypt 哈希持久保存，重启不会重新生成或覆盖。修改密码会使全部管理页会话失效。
3. 进入「API Keys」，输入客户端名称，系统自动生成 `sk-` 开头的随机 Key。完整 Key 只在创建时显示一次；服务器只存 SHA-256 哈希，列表只显示脱敏标识。
4. 将 Key 手动保存在 MCP 客户端或你自己的 CLI 配置里。不同应用可创建不同名称的 Key，撤销后后续请求立即失效；已在队列中的任务继续完成。

管理页使用 HttpOnly Cookie 会话；创建和撤销 Key 需要管理员登录。API Key 不能管理密码或其他 Key。所有生图 API、MCP 握手和工具发现、健康状态与接口文档都需要 `Authorization: Bearer sk-…`。仅 HTML/静态文件和登录入口供登录前访问。

CLI 使用 `sif configure` 保存用户创建的 Key，后续自动读取用户目录中的 `~/.sif/config.json`。原 `IMAGE_API_KEY` 启动配置已停用。可选 `SIF_ADMIN_PASSWORD` 仅用于首次初始化，已设置的持久密码始终优先。

## Agent 通过 MCP 生图

端点：`http://127.0.0.1:8011/mcp`，使用官方 MCP Python SDK 的 Streamable HTTP。支持以下配置结构的客户端可参考：

```json
{
  "mcpServers": {
    "sif": {
      "url": "http://127.0.0.1:8011/mcp",
      "headers": {"Authorization": "Bearer YOUR_API_KEY"}
    }
  }
}
```

将 `YOUR_API_KEY` 替换为在 SIF 控制台创建的密钥，具体字段与密钥存储方式以客户端要求为准。管理页「MCP 接入」也可复制当前服务地址和配置示例。

工具：`get_capabilities`、`get_readiness`、`text_to_image`、`image_to_image`、`upload_image`、`wait_for_job`、`get_job`、`get_queue`、`get_result`。生图工具立即返回持久化任务 ID 和排队顺位，然后调用 `wait_for_job` 由服务端等待状态事件，避免 Agent 反复 sleep / 查询。MCP 使用标准 Streamable HTTP / SSE 响应，原 REST JSON 接口兼容保留。MCP 默认关闭扩写。为每次预期生成传入唯一 `idempotency_key`，丢失响应后用同一 Key 和参数恢复。

`wait_for_job(job_id, timeout_seconds=25)` 默认等待最多 25 秒，可设置 1–600 秒，但应小于客户端工具超时。支持的客户端可接收状态、排队顺位和等待时间通知。超时返回 `wait_timed_out=true` 时，用同一任务 ID 再调用等待工具，不额外 sleep 或重新提交。连接断开或取消等待不会取消生图。`unknown` 状态直接返回以便人工检查。进度通知不能保证客户端自动延长超时。

SIF 到 ComfyUI 的正常完成等待也使用 WebSocket `/ws?clientId=原任务ID`，不再由 `ComfyClient.wait` 每两秒轮询历史。建立订阅后读取一次历史以覆盖快速完成或重启恢复；随后监听所属 `prompt_id` 的完成/失败事件，收到事件后读取最终历史。断线按原客户端 ID 重连，连接失败使用 WebSocket 库的退避机制；不重提 `/prompt`。`execution_success` 可能早于历史写入，因此尚无历史时继续等待最终 `executing(node=null)`。超时只做一次有界恢复快照，仍无法确认则保留未决状态；原有 `unknown` 检查与人工恢复规则继续适用。

参考图片通过 HTTP 上传后取得 MinIO 地址，再交给 MCP。CLI `--image` 支持本地文件和已有 MinIO 地址。生成结果通过返回的 SIF 地址下载，携带 API Key；CLI `status JOB_ID --output 图片.png` 可以恢复并下载。详见 [MCP 参考](skills/sif-image-generation/references/mcp.md)。

## SIF Client 通过 REST 生图

Client 是独立的 Python 3.10+ 项目，可复制到另一台机器运行。在仓库根目录安装并配置一次。

```powershell
python -m pip install ./sif_client
sif configure --base-url http://127.0.0.1:8011
```

在隐藏输入提示中粘贴控制台创建的 Key。后续命令自动从 `~/.sif/config.json` 读取地址与 Key。该文件为本地明文 JSON，POSIX 权限为 `0600`，Windows 由用户目录 ACL 控制。`sif config` 查看配置状态，`sif logout` 删除本地保存的 Key。详细安装、配置优先级和恢复用法见 [Client 使用说明](../sif_client/README.md)。

Client 使用 REST `/v1/**` 完成上传、生成、等待和下载。MCP 宿主应用通过自己的连接配置保存 `/mcp` 地址与 Key。两种入口共用 Server 的持久化任务队列。

```powershell
sif config
sif queue
sif jobs
```

配置后直接使用以下命令。

```powershell
# 查看能力、参数和模型就绪状态
sif capabilities
sif readiness

# 文生图：按当前 Skill 建议使用 fast、2K、16:9，关闭扩写
sif t2i --tier fast --prompt "A red ceramic teapot on a wooden table beside a window, soft natural light, realistic product photography" --mp 2.25 --aspect-ratio 16:9 --no-expand --output outputs/teapot.png

# 图生图：pro，保留输入图比例
sif i2i --tier pro --image source.png --prompt "Edit <Picture 1>. Change the red teapot to cobalt blue. Preserve its shape, framing, and window light." --mp 4 --no-expand --output outputs/edited.png

# 多参考图组合，精确指定画布，请求两张候选图
sif i2i --tier fast --image person.png --image jacket.png --prompt "Edit <Picture 1>. Dress the person in the jacket from <Picture 2>. Preserve the person's face and pose." --width 2048 --height 1152 --n 2 --no-expand --output-format webp --output outputs/outfit.webp

# fast 预览，关闭扩写，指定随机种子
sif t2i --tier fast --prompt "A red ceramic teapot on a wooden table" --mp 1 --no-expand --seed 42 --output outputs/draft.png

# 断线后继续查询并下载原任务，不重新生图
sif status JOB_UUID --output outputs/recovered.png
```

CLI 只依赖 Python 3.10+ 标准库，不依赖 FastAPI、Docker Python SDK 或已有网关。过程写入 stderr，成功后向 stdout 输出 JSON，包括任务 ID、实际 seed、实际提示词及下载后的图片绝对路径；失败时返回非零退出码。默认不覆盖已有文件。

## 档位与参数

| 英文档位 | 主模型 | 建议用途 |
| --- | --- | --- |
| `pro` | `minimax_h3_image_pruned_int8_convrot.safetensors` | 重视细节的最终候选，或用户指定 INT8 |
| `standard`（默认） | `minimax_h3_image_pruned_w4a8.safetensors` | 日常生成与编辑 |
| `fast` | `MiniMax-H3-Image-Pruned-Q4_K_M.gguf` | 预览、试稿或希望减小权重占用 |

档位表示模型预设，不保证某次生成的绝对速度或质量排序。

- `t2i` 和 `i2i` 均支持三档、提示词扩写开关和 0.1–20MP。默认 4MP；MCP 默认关闭扩写，REST/CLI 保留原有扩写开启默认值。Skill 建议 CLI 显式加 `--no-expand`，仅在用户明确要求扩写时开启。
- 两种模式均支持 1:3–3:1 范围内的数值比例，如 `5:4`、`2.39:1`。图生图默认采用第一张参考图的 EXIF 校正后比例，可显式指定其他输出比例。
- `width`、`height` 可精确指定画布，均为 32 的倍数，最大边长 16384，总面积最高 20MP。精确尺寸与 MP/比例两种配置互斥。响应返回实际宽高和 `actual_megapixels`。
- 图生图支持 1–16 张有序参考图，使用 `images` 数组或重复 CLI `--image`，顺序对应 `<Picture 1>` 至 `<Picture 16>`。旧单图 `image` 参数继续可用。所有参考图分别接入 H3 和启用的扩写器。
- `reference_mode=latent` 默认同时提供 VAE 与视觉参考，`vision` 仅提供文本编码器的视觉参考。参考图尺寸与输出画布分别计算。
- `n=1..10` 在一个持久化工作流中创建独立单图采样节点，按基础 seed 递增并返回每张图的 seed。任务仍共用一条串行队列。
- 输出支持 PNG、JPEG、WebP。SIF 负责 JPEG/WebP 编码，可设置 `output_quality=0..100`。`get_result(response_format="b64_json")` 和 REST `/jobs/{id}/result?response_format=b64_json` 可返回内联结果，合计原始图片大小最高 128MiB。
- `prompt_expansion` 可设置扩写 token 预算、thinking、temperature、top_k/top_p/min_p、重复与存在惩罚、独立 seed 和追加指令，须开启扩写。当前安装的扩写器为 Qwen3.5 9B。
- 沿用官方 `1MP=1024×1024` 的定义，宽高按 32 对齐，实际面积可能略有偏差。
- 采样固定为 6 步、CFG 1、Euler、Simple、denoise 1，不暴露容易破坏蒸馏模型采样的覆盖参数。
- 输入图片支持 PNG/JPEG/WebP，最大 25 MiB、40 源 MP。API 接受配置范围内的 MinIO 图片 URL、上传 ID 或图片 Data URI。本地文件通过 HTTP 或 CLI 上传。

完整参数与 HTTP 调用见 [Skill API 参考](skills/sif-image-generation/references/api.md)。

## MinIO 图片上传与 24 小时清理

`POST /v1/uploads` 支持原始图片字节和 multipart `file` 字段。配置 MinIO 后，接口返回图片 `url`、上传 ID、尺寸、SHA-256 和 `expires_at`。CLI `upload 图片路径` 同样返回这份描述，`i2i --image` 可直接传 MinIO 图片地址，也可继续传本地文件。MCP `image_to_image` 的 `images` 接收这些地址。

SIF 通过服务端 MinIO 凭据拉取图片，在入队前校验格式、大小与内容指纹并保存本地快照。已配置的 FREYJA `video-assets` 图片地址也可直接引用。地址对应的来源主机和桶由服务端配置，SIF 使用内部 MinIO 连接读取对象。

上传使用独立私有桶 `sif-uploads` 的 `references/` 前缀，返回的预签名读取地址有效期为 24 小时。SIF 每分钟删除超过 24 小时的临时对象，并清理过期本地输入缓存。队列中、执行中或待确认任务使用的本地快照保留到任务结束。MinIO 同时配置一天生命周期作为停机兜底，停机期间的实际删除时间由 MinIO 生命周期扫描控制。

在本模块私有 `.env` 配置 `SIF_MINIO_ENDPOINT`、`SIF_MINIO_PUBLIC_ENDPOINT`、`SIF_MINIO_ACCESS_KEY`、`SIF_MINIO_SECRET_KEY`、`SIF_MINIO_BUCKET`、`SIF_MINIO_READ_BUCKETS` 和 `SIF_MINIO_URL_ALIASES`。客户端可访问的公共端点用于生成图片 URL，容器内端点用于上传和读取。具体字段见 `.env.example`。所有 SIF 上传和 MCP 调用需要 SIF API Key，MinIO URL 使用自身的短期签名。

```powershell
sif upload reference.png
sif i2i --tier fast --image "MINIO_IMAGE_URL" --prompt "Edit <Picture 1>. Change the background to a sunlit garden. Preserve the person's identity." --mp 2.25 --no-expand --output edited.png
```

官方资料和本地能力映射见 [H3 能力说明](skills/sif-image-generation/references/h3-capabilities.md)。涵盖模型文档、多参考图示例、ComfyUI 节点和托管 API 的接口范围。

## 本地 WSL Docker

本模块拥有单独的 `compose.yaml`、镜像、容器和数据卷。宿主机仅发布 `127.0.0.1:8011`。容器内监听 `0.0.0.0` 用于 Docker 端口转发，不采用 host 网络。

```powershell
# 启动 / 更新独立容器
.\comfy_image_service\start.ps1

# 查看日志
wsl -d Ubuntu --exec docker logs --tail 50 sif
```

也可以在 WSL 内运行：

```bash
cd /mnt/f/idea_wps/freyja/comfy_image_service
docker compose up -d --build
docker compose logs --tail 50
```

首次在另一台机器安装：复制 `.env.example` 为 `.env`，配置现有 ComfyUI 的 `COMFY_URL`，启动后查看初始密码并登录控制台创建客户端 Key。Client 安装后执行 `sif configure --base-url 服务地址` 保存 Key，也可通过 `--config 文件` 或 `SIF_CONFIG` 指定独立配置。原 `COMFY_IMAGE_*` 客户端环境变量仍作为兼容别名接受；服务不再接受原启动 Key。

Docker 镜像为 `sif:local`。Compose 项目标识保留 `comfy-image-service`，以便改名升级时复用现有数据卷 `comfy-image-service_image_data`，保留任务数据库、上传图片和生成结果。`apps/` 与 `skills/` 以只读挂载形式读取本地配置，更新工作流或 Skill 后无需重启。服务代码修改后重新构建本容器。运行使用单 worker，避免同一 GPU 被此服务重复提交任务。

ComfyUI 需要可用的 Image VAE、Qwen3-VL 文本编码器、Qwen3.5 扩写模型、三个主模型与 ComfyUI-GGUF。当前工作流由 SIF 计算输出尺寸，直接使用核心 H3 Conditioning 节点准备参考图。`comfy_nodes/` 中的旧 20MP 节点继续供历史工作流使用，GGUF 兼容补丁按目标节点版本安装。

## 给其他 AI 的标准 Skill

入口：`skills/sif-image-generation/SKILL.md`，优先使用已配置的 SIF MCP，包含模式选择、档位选择、分辨率、扩写选择、命令调用和恢复规则。MCP 使用方法在 `references/mcp.md`，REST Client 使用方法在 `references/client.md`。`scripts/generate.py` 为独立 Client 的兼容入口，下载 Skill 后需另行安装 Client。Skill 本身不包含认证凭据，也不会读取服务配置获取 Key。

已启动的服务也提供鉴权下载：

- `GET /v1/skill`：Skill 文本。
- `GET /v1/skill.zip`：下载 `sif-image-generation.zip`，包含 SKILL.md、UI 元数据、MCP/Client/API 参考文档和 Client 兼容入口。

## 新工作流 / App 注册

`apps/*/app.json` 是本服务的显式注册描述。服务在每次能力查询或任务提交时重新发现目录，支持新增与更新，不导入任何原网关模块。

ComfyUI App 模式提供界面；要从 CLI 调用其工作流，需要一次性导出 **API 格式 JSON** 并写明参数绑定。可参考 `apps/minimax-h3-image/` 的结构：

1. 新建独立目录，放入 `app.json` 和 `t2i.api.json` / `i2i.api.json`。
2. 设置唯一 `id`、支持的模式、`pro/standard/fast` 模型预设，以及 prompt、seed、扩写开关、width/height 与图片输入的节点绑定。H3 模板还声明 `conditioning_node`、`expansion_node`、`expansion_template_node`。旧 MP/比例绑定格式继续可读取。
3. `output_node` 指向 SaveImage；`prompt_preview_node` 指向扩写开关之后的 PreviewAny。
4. 目录放到 `apps/` 后，`capabilities` 立即可见。用 `--app 新ID` 调用。

注册文件指向的模板不得逃出本 App 目录；无效或 ID 冲突的注册被禁用，并在 `/v1/apps` 的 diagnostics 中显示。已有任务使用提交时的不可变工作流快照，不受注册后续更新影响。注册目录属于受信任的本机配置，不通过公共 API 接受任意工作流代码。

原生 Python 模式也可通过 `python -m comfy_image_service.cli validate 路径` 校验，或通过 `register 路径` 注册新的受信任目录。

## 任务与接口

`POST /v1/t2i` / `/v1/i2i` 返回 HTTP 202。任务先写 SQLite，成功后才排队；数据库写入失败不会伪造任务 ID。所有 REST/MCP 请求共用一条持久化 FIFO 队列，一个任务完成后才提交下一个。`GET /v1/queue` 返回当前执行、等待任务和顺位；管理页也自动刷新队列。进程文件锁阻止多个 SIF worker 同时使用一个数据目录。

`Idempotency-Key` 防止命令行在提交响应丢失后生成重复任务。服务不自动重试 ComfyUI 的提交 POST；重启后优先完成已有 backend prompt ID 的任务，再执行等待任务。中断或超时导致状态不明时标记 `unknown`，暂停后续生成并继续查询已有结果；无法自动确定时，管理员确认 ComfyUI 中任务已结束后，可在管理页释放队列。超时不会中断其他 ComfyUI 任务，也不调用全局 interrupt。

接口文档见 [SIF OpenAPI](http://127.0.0.1:8011/docs)。命令行封装上传、提交、事件等待与下载，REST 长等待入口为 `GET /v1/jobs/{id}/wait?timeout_seconds=25`。

## 验证

```powershell
comfy_image_service/.venv/Scripts/python.exe -m unittest discover -s comfy_image_service/tests -v
```

测试覆盖密码初始化与修改、Key 哈希存储/撤销、会话与 API 权限隔离、MCP 握手和生图、严格 FIFO 与单 worker、超时阻塞和重启恢复，以及六种模式/档位装配、输入限制、幂等与冲突、数据库持久化、注册热发现、模板越界和 ComfyUI 部分输出拒绝。真实验收记录与页面截图保存在本地 `data/verification/`，不会纳入 Git。20MP 是参数上限，低分辨率生图验证不等于已经实测 20MP 生图。

2026-11-11：Windows 与实际 Docker 依赖环境均通过 12 项测试；Windows CLI 经本地 Docker 网关完成 t2i/i2i × pro/standard/fast 六次 0.25MP 生图与本地下载。已检查生成图片可解码，并确认图生图茶壶改为蓝色、保留原图构图。

2026-11-12 的 H3 能力扩展使用合成图片和模拟后端验证参考顺序、画布边界、多图输出、格式转换、MCP/CLI、历史快照和幂等键兼容，实际后端仅执行只读节点检查。
