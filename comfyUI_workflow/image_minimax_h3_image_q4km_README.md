# MiniMax H3 Image Q4_K_M：文生图 / 图生图

## 导入与使用

- `image_minimax_h3_image_t2i_q4km.json`：拖入 ComfyUI，在「用户提示词 / 画面描述或编辑要求」填写画面描述后运行。
- `image_minimax_h3_image_i2i_q4km.json`：拖入 ComfyUI，在 LoadImage 节点上传待编辑图片，在「用户提示词 / 画面描述或编辑要求」使用 `Edit <Picture 1>. ...` 描述修改要求。
- 「开启提示词扩写（默认开启）」控制官方提示词扩写链路：开启时由 Qwen3.5 9B 将简短描述扩写为详细英文提示词；关闭时直接使用用户原文，跳过扩写模型加载、文本生成和 JSON 提取。
- 「实际使用的提示词（扩写结果 / 原文）」展示最终传入 H3 的文本。预览位于开关之后，关闭扩写时不会因预览而触发扩写计算。
- 文生图和图生图分别复用各自官方模板的系统提示词与任务规范。图生图同时把上传图片交给扩写模型，结合图片内容组织编辑要求。
- 两份工作流也已放入远端 `/home/freyja/ComfyUI-Official/user/default/workflows/`，可从工作流列表打开。
- 两个流程都使用「输出尺寸：画幅比例 + MP（支持超过 16MP）」节点控制宽高：选择 `aspect_ratio`，填写 `megapixels`，宽高会自动换算并对齐到 32 的倍数。默认 1:1、4MP，即 2048×2048。
- MP 沿用官方节点的定义：`1MP = 1024×1024` 像素。方图 16MP 为 4096×4096，20MP 约为 4576×4576，64MP 为 8192×8192；按 32 对齐后实际像素总量可能略有偏差。
- MP 可填写 0.1–256，支持 20、24、32 等超过 16 的值；每条边仍不能超过 ComfyUI 的 16384 像素上限，因此宽画幅的可用 MP 上限低于方图。该范围表示尺寸节点允许的输入范围，实际生图仍取决于显存和模型可用范围。
- 节点保留官方实时尺寸预览，并增加「实际输出宽度（px）」「实际输出高度（px）」执行结果预览。图生图按所选比例输出；需要保留原图构图时，请选择与参考图一致的比例。
- 保持 `steps=6`、`cfg=1`、`euler`、`simple`、`denoise=1.0`。图生图通过 H3 原生参考图条件编辑；修改幅度由提示词控制。
- 开启扩写时可以填写中文简短描述；关闭时建议自行提供详细英文提示词。图生图第一个动态输入 `images.image_0` 对应提示词中的 `<Picture 1>`。

## 已安装模型与依赖

目标：`ssh freyja` → WSL `Ubuntu-22.04` → `/home/freyja/ComfyUI-Official`。

| 用途 | 文件位置（相对 ComfyUI 根目录） |
| --- | --- |
| 本次下载的 Q4_K_M 主模型 | `models/unet/MiniMax-H3-Image-Pruned-Q4_K_M.gguf` |
| 复用的文本编码器 | `models/text_encoders/qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors` |
| 复用的提示词扩写模型 | `models/text_encoders/qwen3.5_9b_w4a8_gptq.safetensors` |
| 复用的 24 通道图片 VAE | `models/vae/minimax_h3_image_vae_fp16.safetensors` |
| 本次安装的作者兼容补丁 | `custom_nodes/minimax_h3_image_gguf_fix.py` |
| 超过 16MP 的独立尺寸节点 | `custom_nodes/freyja_resolution_selector.py` |

Q4_K_M 指主扩散模型的量化格式。文本编码器复用远端现有的 NVFP4/AWQ 版本，已在该 RTX 4080 环境实际验证。

现有 ComfyUI 为 0.45.0，已安装 ComfyUI-GGUF。补丁通过内存注入补齐 H3 Image GGUF 所需的 6 步 PDD sigma 与 24 通道配置；ComfyUI 已在空闲队列时重启并成功加载。

尺寸节点继承官方 `ResolutionSelector` 的界面与比例选项，扩展 MP 上限，并使用相同的换算和对齐方式；没有修改 ComfyUI 核心文件。迁移到其他 ComfyUI 时，将仓库中的 `comfy_nodes/freyja_resolution_selector.py` 复制到其 `custom_nodes/` 目录并重启。

下载源：[Abiray/MiniMax-H3-Image-GGUF 镜像](https://hf-mirror.com/Abiray/MiniMax-H3-Image-GGUF)。固定版本 `f41fd51d70cf5cc422a7f91e44d861b89605cc49`，文件大小 `11573106144` 字节，已核对 SHA-256：

```text
be2b96003c01e046c62f534c08caf07748f6e4c6259df3a00cc892a2bb4bc7e7
```

远端下载与校验记录保存在 `/home/freyja/model-downloads/h3-image-q4km/`。

## 实际验证

2026-11-11，在远端 RTX 4080 上分别提交 512×512、6 步测试，两次均返回 `success` 且无节点校验错误。

| 流程 | 结果 | 耗时 |
| --- | --- | --- |
| 文生图 | 红色陶瓷茶壶 | 22.896 秒（包含首次加载） |
| 图生图 | 将同一茶壶改为钴蓝色，保留构图与环境 | 22.096 秒 |

对应本地验证图片：`examples/h3_image_q4km_t2i.png`、`examples/h3_image_q4km_i2i.png`。上述为初始版本的 512×512 测试，不能代表当前默认 4MP 的执行性能，耗时会随分辨率、提示词长度和参考图尺寸变化。

增加提示词扩写后，使用中文短句分别完成下列 512×512 实测，四次均成功出图：

| 流程 | 扩写开关 | 耗时 |
| --- | --- | --- |
| 文生图 | 开 | 约 50 秒 |
| 图生图 | 开 | 约 68 秒 |
| 文生图 | 关 | 约 16 秒 |
| 图生图 | 关 | 约 22 秒 |

开启时确认执行 `TextGenerateV2`，并将扩写后的非空英文文本传入 H3；关闭时确认最终提示词与输入原文完全一致，且执行事件中没有扩写模型加载、文本生成或 JSON 提取节点。以上耗时受当时缓存状态影响，不能直接视为性能对比。

远端完整验证记录：`/home/freyja/model-downloads/h3-image-q4km/prompt-expansion-verification.json`。更新前的远端工作流已备份到同目录的 `before-prompt-expansion-20261111-164844/`。

增加 MP 尺寸控制后，完成 32 组与官方节点的换算一致性检查，并验证 20MP、32MP、64MP、256MP 的尺寸及单边上限校验。已注册的扩展节点通过 ComfyUI API 成功执行 20MP 方图尺寸计算，返回 4576×4576；该检查未执行 20MP 生图。

两个完整流程分别使用 0.25MP 的 16:9（672×384）与 3:2（640×416）实际出图成功，确认 MP 节点正确控制最终图像宽高。提示词扩写的默认开启状态保持不变。远端验证记录：`/home/freyja/model-downloads/h3-image-q4km/mp-verification.json`；本次更新前的版本保存在 `before-mp-selector-20261111-170023/`。
