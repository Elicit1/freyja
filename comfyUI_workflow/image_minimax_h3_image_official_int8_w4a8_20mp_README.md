# MiniMax H3 Image：官方结构 INT8 / W4A8 工作流（20MP）

## 文件

- `image_minimax_h3_image_t2i_official_int8_w4a8_20mp.json`：文生图。
- `image_minimax_h3_image_i2i_official_int8_w4a8_20mp.json`：图生图 / 参考图编辑。
- `comfy_nodes/freyja_h3_image_20mp.py`：独立 20MP 尺寸节点；迁移时复制到目标 ComfyUI 的 `custom_nodes/` 目录并重启。

两个工作流已同步到 `ssh freyja` 的 WSL `Ubuntu-22.04`，目录为 `/home/freyja/ComfyUI-Official/user/default/workflows/`，20MP 节点也已安装并加载。

## 主模型选择

在「主模型：W4A8 / INT8（在 unet_name 下拉切换）」中选择：

| 格式 | 模型文件（`models/diffusion_models/`） |
| --- | --- |
| W4A8（默认） | `minimax_h3_image_pruned_w4a8.safetensors` |
| INT8 | `minimax_h3_image_pruned_int8_convrot.safetensors` |

使用官方标准 `UNETLoader`；保持 `weight_dtype=default`，让 ComfyUI 根据文件中的量化信息加载。两份工作流均可通过这个下拉框切换主模型。

默认文本编码器为远端现有的 `qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors`，Image VAE 为 `minimax_h3_image_vae_fp16.safetensors`。文本编码器和主扩散模型分别加载，主模型的 INT8/W4A8 切换不要求更换文本编码器。

## 提示词扩写

在「用户提示词 / 画面描述或编辑要求」填写需求。「开启提示词扩写（默认开启）」控制是否使用官方提示词扩写链路：

- 开启：由已安装的 `qwen3.5_9b_w4a8_gptq.safetensors` 配合官方系统提示词、任务规范扩写，再提取 `expanded_caption`。
- 关闭：直接使用用户原文，跳过扩写模型与生成步骤。
- 「实际使用的提示词（扩写结果 / 原文）」展示最终传给 H3 的文本。预览位于开关之后，不会因预览强制运行未选择的扩写分支。

图生图需在 LoadImage 上传图片。提示词用 `<Picture 1>` 指代它；开启扩写时，扩写模型也读取参考图。文生图与图生图分别保留各自官方模板的扩写规范。

## MP 与画幅

- 文生图：使用「输出尺寸：画幅比例 + MP（最高 20MP）」选择画幅和 MP。
- 图生图：使用「输入图按原比例缩放：目标 MP（最高 20）」填写 MP，保留原图比例，再通过官方缩放节点对齐到 32 的倍数，并从图像读取最终输出宽高。保留官方原图与编辑结果的滑动对比预览。
- 默认 4MP；两类 MP 输入上限均为 20。文生图最低 0.1MP，图生图缩放最低 0.01MP，沿用各自官方节点下限。
- 使用官方 `1MP=1024×1024` 像素的约定。4MP 方图为 2048×2048；20MP 方图约 4576×4576，对齐后实际像素总量可能略有偏差。

20MP 扩展节点继承官方尺寸计算与图像缩放实现，仅扩展 MP 上限并明确校验范围，不修改 ComfyUI 核心文件。实际高分辨率生图仍受显存与模型运行能力影响。

## 与官方模板的对应关系

文生图将官方子图展开为完整节点，保留模型加载、用户提示词、扩写模型、官方扩写规范、字符串替换、JSON 提取、条件开关、最终文本预览、H3 条件、采样、解码与保存链路。

图生图保留官方完整结构，包括输入图按 MP 缩放、32 对齐、尺寸读取、图像辅助扩写和前后对比。采样参数均保持 6 步、CFG=1、Euler、Simple、denoise=1。

官方模板来源为远端已安装的 `comfyui-workflow-templates` 包中的 `image_minimax_h3_image_t2i.json` 与 `image_minimax_h3_image_edit.json`；运行环境为 ComfyUI 0.45.0 / RTX 4080。

## 实际验证

2026-11-11，以下组合均已实际出图成功：

| 主模型 | 流程 | 扩写开关 | 测试输出 |
| --- | --- | --- | --- |
| W4A8 | 文生图 | 开 | 672×384 |
| W4A8 | 图生图 | 开 | 512×512 |
| INT8 | 文生图 | 关 | 672×384 |
| INT8 | 图生图 | 关 | 512×512 |

开启时确认使用扩写后的提示词，关闭时确认原文透传且文本生成节点不执行。图生图确认官方对比预览执行成功。测试恢复过程中复用了 ComfyUI 的节点缓存，耗时不用于模型性能对比。

另外通过 API 验证了 20MP 文生图尺寸计算和 20MP 输入图缩放，方图均得到 4576×4576；未执行 20MP 扩散生图。两个 MP 节点的上限均为 20，原生官方节点的 16MP 上限保持原有配置。

远端验证记录：`/home/freyja/model-downloads/h3-image-native-20mp/verification.json`。验证用的 API 请求为官方图像对比控件补充 `compare_view=null`；在 ComfyUI 界面运行时，该参数由控件自动生成。
