# Comfy Image Service

给本地 AI / Agent 使用的独立命令行生图网关。代码、App 注册、认证、SQLite 任务记录和 Docker 数据卷均位于这个模块；运行在本机 Ubuntu WSL 的独立容器 `comfy-image-service`，访问地址为 `http://127.0.0.1:8011`。GPU 生图继续调用已配置的 ComfyUI。

## 直接调用

当前机器已准备私有 `.env` 和 `.client.json`，CLI 会自动读取连接配置，命令中无需携带令牌。在仓库根目录执行：

```powershell
# 查看能力、参数和模型就绪状态
python comfy_image_service/generate.py capabilities
python comfy_image_service/generate.py readiness

# 文生图：standard，按当前 Skill 建议关闭扩写，4MP，16:9
python comfy_image_service/generate.py t2i --tier standard --prompt "一只红色陶瓷茶壶放在窗边木桌上，柔和自然光，写实产品摄影" --mp 4 --aspect-ratio 16:9 --no-expand --output outputs/teapot.png

# 图生图：pro，保留输入图比例
python comfy_image_service/generate.py i2i --tier pro --image source.png --prompt "Edit <Picture 1>. 把红色茶壶改成钴蓝色，保留形状、构图和窗边光线。" --mp 4 --no-expand --output outputs/edited.png

# fast 预览，关闭扩写，指定随机种子
python comfy_image_service/generate.py t2i --tier fast --prompt "A red ceramic teapot on a wooden table" --mp 1 --no-expand --seed 42 --output outputs/draft.png

# 断线后继续查询并下载原任务，不重新生图
python comfy_image_service/generate.py status JOB_UUID --output outputs/recovered.png
```

CLI 只依赖 Python 3.10+ 标准库，不依赖 FastAPI、Docker Python SDK 或已有网关。过程写入 stderr，成功后向 stdout 输出 JSON，包括任务 ID、实际 seed、实际提示词及下载后的图片绝对路径；失败时返回非零退出码。默认不覆盖已有文件。

## 档位与参数

| 英文档位 | 主模型 | 建议用途 |
| --- | --- | --- |
| `pro` | `minimax_h3_image_pruned_int8_convrot.safetensors` | 重视细节的最终候选，或用户指定 INT8 |
| `standard`（默认） | `minimax_h3_image_pruned_w4a8.safetensors` | 日常生成与编辑 |
| `fast` | `MiniMax-H3-Image-Pruned-Q4_K_M.gguf` | 预览、试稿或希望减小权重占用 |

档位表示模型预设，不保证某次生成的绝对速度或质量排序。

- `t2i` 和 `i2i` 均支持三档、提示词扩写开关和 0.1–20MP。服务默认 4MP、扩写开启；当前 Skill 建议调用时显式加 `--no-expand`，仅在用户明确要求扩写时开启。
- 文生图提供 1:1、2:3、3:2、3:4、4:3、9:16、16:9、21:9；图生图按目标 MP 缩放并保留原图比例。
- 沿用官方 `1MP=1024×1024` 的定义，宽高按 32 对齐，实际面积可能略有偏差。
- 采样固定为 6 步、CFG 1、Euler、Simple、denoise 1，不暴露容易破坏蒸馏模型采样的覆盖参数。
- 输入图片支持 PNG/JPEG/WebP，最大 25 MiB、40 源 MP。API 接受上传 ID 或图片 Data URI，不读取客户端提交的服务器文件路径或 HTTP 图片 URL。

完整参数与 HTTP 调用见 [Skill API 参考](skills/comfy-image-generation/references/api.md)。

## 本地 WSL Docker

本模块拥有单独的 `compose.yaml`、镜像、容器和数据卷。宿主机仅发布 `127.0.0.1:8011`。容器内监听 `0.0.0.0` 用于 Docker 端口转发，不采用 host 网络。

```powershell
# 启动 / 更新独立容器
.\comfy_image_service\start.ps1

# 查看日志
wsl -d Ubuntu --exec docker logs --tail 50 comfy-image-service
```

也可以在 WSL 内运行：

```bash
cd /mnt/f/idea_wps/freyja/comfy_image_service
docker compose up -d --build
docker compose logs --tail 50
```

首次在另一台机器安装：复制 `.env.example` 为 `.env`，配置现有 ComfyUI 的 `COMFY_URL`，用 `python -c "import secrets; print(secrets.token_urlsafe(32))"` 生成独立 `IMAGE_API_KEY`。CLI 使用 `COMFY_IMAGE_BASE_URL` / `COMFY_IMAGE_API_KEY`，或私有 JSON `{"base_url":"http://127.0.0.1:8011","api_key":"..."}`；后者通过 `--config 文件`（放在子命令之前）指定。私有配置已被 Git 和 Docker 构建上下文排除。

Docker 数据卷 `comfy-image-service_image_data` 持久保存任务数据库、上传图片和生成结果。`apps/` 与 `skills/` 以只读挂载形式读取本地配置，更新工作流或 Skill 后无需重启。服务代码修改后重新构建本容器。运行使用单 worker，避免同一 GPU 被此服务重复提交任务。

ComfyUI 需要可用的 Image VAE、Qwen3-VL 文本编码器、Qwen3.5 扩写模型、三个主模型与 ComfyUI-GGUF。`comfy_nodes/` 包含本模块工作流所需的 20MP 节点与 GGUF 作者兼容补丁；当前目标 ComfyUI 已安装它们。迁移到新的 ComfyUI 时，将这两个文件放到其 `custom_nodes/`，在队列空闲时重启 ComfyUI。

## 给其他 AI 的标准 Skill

入口：`skills/comfy-image-generation/SKILL.md`，包含模式选择、档位选择、分辨率、扩写选择、命令调用和恢复规则。`scripts/generate.py` 可直接随 Skill 分发。Skill 本身不包含认证凭据。

已启动的服务也提供鉴权下载：

- `GET /v1/skill`：Skill 文本。
- `GET /v1/skill.zip`：包含 SKILL.md、UI 元数据、参考文档和调用脚本的标准目录包。

## 新工作流 / App 注册

`apps/*/app.json` 是本服务的显式注册描述。服务在每次能力查询或任务提交时重新发现目录，支持新增与更新，不导入任何原网关模块。

ComfyUI App 模式提供界面；要从 CLI 调用其工作流，需要一次性导出 **API 格式 JSON** 并写明参数绑定。可参考 `apps/minimax-h3-image/` 的结构：

1. 新建独立目录，放入 `app.json` 和 `t2i.api.json` / `i2i.api.json`。
2. 设置唯一 `id`、支持的模式、`pro/standard/fast` 模型预设，以及 prompt、MP、seed、扩写开关、画幅或图片输入的节点绑定。
3. `output_node` 指向 SaveImage；`prompt_preview_node` 指向扩写开关之后的 PreviewAny。
4. 目录放到 `apps/` 后，`capabilities` 立即可见。用 `--app 新ID` 调用。

注册文件指向的模板不得逃出本 App 目录；无效或 ID 冲突的注册被禁用，并在 `/v1/apps` 的 diagnostics 中显示。已有任务使用提交时的不可变工作流快照，不受注册后续更新影响。注册目录属于受信任的本机配置，不通过公共 API 接受任意工作流代码。

原生 Python 模式也可通过 `python -m comfy_image_service.cli validate 路径` 校验，或通过 `register 路径` 注册新的受信任目录。

## 任务与接口

`POST /v1/t2i` / `/v1/i2i` 返回 HTTP 202。任务先写 SQLite，成功后才排队；数据库写入失败不会伪造任务 ID。所有 `/v1/**` 接口和图片下载都需要 Bearer 认证，`/health` 仅返回基础状态。

`Idempotency-Key` 防止命令行在提交响应丢失后生成重复任务。服务不自动重试 ComfyUI 的提交 POST；已经保存 backend prompt ID 的任务在重启后继续查询。中断发生在无法判断是否已提交的窗口时标记 `unknown`，不自动重复生图。超时不会中断其他 ComfyUI 任务，也不调用全局 interrupt。

接口文档：[http://127.0.0.1:8011/docs](http://127.0.0.1:8011/docs)。这是独立的异步 REST 接口；命令行已封装上传、提交、轮询与下载。

## 验证

```powershell
comfy_image_service/.venv/Scripts/python.exe -m unittest discover -s comfy_image_service/tests -v
```

测试覆盖鉴权、六种模式/档位装配、输入限制、幂等与冲突、数据库持久化、注册热发现、模板越界和 ComfyUI 部分输出拒绝。真实命令行测试记录位于本地 `data/verification/live-verification.json`，图片保存在同目录；20MP 是已校验的参数上限，低分辨率生图验证不等于已经实测 20MP 生图。

2026-11-11：Windows 与实际 Docker 依赖环境均通过 12 项测试；Windows CLI 经本地 Docker 网关完成 t2i/i2i × pro/standard/fast 六次 0.25MP 生图与本地下载。已检查生成图片可解码，并确认图生图茶壶改为蓝色、保留原图构图。
