# MiniMax H3 接续部署与验收

2026-11-09。H3 接续与参考视频的人工验收标记已移除；不再配置或返回 `status: UNVERIFIED/VERIFIED`、`verified_backend`、`verified_revision`、`verified_backend_signature`。提交依据当前节点、工作流接线、媒体预算和准备快照检查。生成后的接缝与控制效果检查仍有价值，但不再是人工放行门槛。H3 与 Union 2.0 统一使用官方节点。

## 使用入口与实际行为

在分镜抽屉或渲染工坊打开「接续模式与片段计划」。接续设置属于单镜头，缺省关闭；关闭时生成请求最多 15 秒，超过 15 秒的旧草稿保留但不可提交。开启后可以设置长时长，产品计划上限为 300 秒；上限不代表目标硬件已能完成对应画质。

1. 选择与镜头生成模式一致的视频提供商、模型和尺寸，开启接续。
2. 选择每段 5 / 8 / 10 / 15 秒档位后自动拆分，新草稿默认 5 秒；旧草稿保留原有段长。也可自定义合法帧数或手动增加/编辑片段。自动计划可以继续手动修改。编辑的是生成帧范围，服务端根据真实重叠计算成片保留范围；目前没有任意成片切点编辑。切换档位若影响已锁定片段或手动素材，需要先处理绑定；不会偷偷丢弃素材。手动段也不能超过已选档位的帧数上限。
3. 每段填写提示词，或选择已启用的 CHAT 模型生成分段提示词。文本规划使用持久化 AI 任务及统一事件日志，系统提示词可通过 `ai.prompt.shot-continuation-plan` 配置覆盖。
4. 明确给片段挂载图片和视频。镜头原素材只进入待分配托盘；未挂载的素材不参与生成。首尾帧工作流也必须给片段明确设置首尾帧，原镜头首尾帧不会隐式带入。尾帧若落在最终裁掉的补齐区会阻止提交。
5. 长参考视频上传或从分镜 / 历史 Take 选择后，默认「按当前分段自动切分并挂载到全部片段」。服务端探测实际源时长，按当前计划生成包含真实重叠的独立绑定，界面立即展示各段源范围；渲染前后台实际裁片和归档。关闭该选项时先放入待分配素材，再用「按时间轴分配」挂载到所选片段，未选目标时分配到全部当前片段。默认源起点对应镜头 0 秒，速度 1:1，源范围不足拒绝，不循环、不拉伸。原始源选择保存到资源托盘便于重用；新增片段不隐式继承视频，计划变化后可再次按时间轴分配。
6. 有参考视频的片段才显示可选 ControlNet，默认关闭。多视频时选择控制源；其他视频仍可作为语义参考。删除所有视频关闭该段 ControlNet，不关闭接续。
7. 检查段数、生成范围、共享帧、成片保留范围、素材源范围和不可用原因。点击渲染后先在后台冻结/归档素材，再进入已有串行视频队列。

随机种子在镜头接续总控中配置，沿用普通镜头的「视频随机种子 Seed」和「刷新种子」。手动编辑后随镜头配置保存；留空保留原种子，刷新按钮立即保存新种子。渲染读取镜头已保存的种子，各片段按现有固定规则派生；素材准备缓存命中后也会按当前镜头种子重新固定片段种子。渲染弹窗不提供种子输入。

接续模式切换 REF2VA / FL2VA 后，片段输入页、素材池与侧边摘要即时切换。REF2VA 使用本段图片、视频、音频参考及可选 ControlNet；FL2VA 使用本段自己的首帧和尾帧。两种模式的编辑素材保留，渲染预览、素材准备和任务快照仅选择当前模式的有效输入；模式也进入计划摘要。两种模式均支持二采放大与人脸修复，二采可选择标准或 [SelfLift](selflift-second-pass.md)。本段提示词按本段顺序引用 `<Picture N>` / `<Video N>`；视频原音由「参考原音」控制。每条启用的 ControlNet 独立计入参考适配槽位，同源的多条控制也分别计数；图片、视频、独立音频和视频原音共同受 `maxMixedAssets` 限制。

上一段真实生成尾部由系统作为技术上下文传递，不占普通引用编号，统一使用官方 `MiniMaxH3AddGuide` 的视频与音频条件。采样 latent 保持原工作流接线，不额外写入前缀或设置噪声遮罩。「锁定共享上下文」选项已移除；旧草稿可以读取，重新保存时不再包含该字段，必须重新预览并准备任务。旧执行快照若要求 `lock_context=true` 会被拒绝，避免按不同语义继续执行。

段间接续与片段内部的上下文窗口分别处理。窗口单独使用官方节点；窗口与 ControlNet 联用仅在具体模型配置 `context_windows_with_control_net: true` 且完成对应真实 GPU 验收后允许。默认关闭该组合，预览及提交均明确阻止；按 5 / 8 / 10 / 15 秒分段、逐段使用 ControlNet 不需要同时开启片段内窗口。

### 段长档位

档位代表单次生成长度，包含共享上下文；实际帧数遵守当前 H3 网格，成片最终仍裁到目标时长。5 秒档减少单次帧数，不能单凭档位保证任意硬件可用。

模型中心的最大参考视频数、每次参考视频总时长和混合素材预算均按每个生成片段独立校验，不累加整条接续计划的切片。上限为 1 时，7 个片段各挂 1 个视频允许通过数量校验；只有同一片段同时挂多个视频才会触发该上限。上一段尾部的 Guide 上下文不占普通参考视频名额。全局验收问题只报告一次，数量超限提示列出受影响片段及模型中心、网关的实际限额。

| 档位 | 实际单段上限 | 默认共享上下文 | 60 秒目标的自动段数 |
| --- | --- | --- | --- |
| 5 秒（新草稿默认） | 124 帧 / 5.167 秒 | 22 帧 / 0.917 秒 | 14 |
| 8 秒 | 192 帧 / 8 秒 | 39 帧 / 1.625 秒 | 10 |
| 10 秒 | 243 帧 / 10.125 秒 | 39 帧 / 1.625 秒 | 7 |
| 15 秒 | 362 帧 / 15.083 秒 | 39 帧 / 1.625 秒 | 5 |

末段不足 124 帧时会向前扩大真实重叠，因此最后一个接续点可能比表中的默认重叠长。源视频切片使用绝对 `sourceStartFrame / sourceEndFrame`，先按同一个 24fps 时间轴重采样再裁帧，避免每段从不同小数秒重新建立帧率相位。编辑源秒数会取消该绑定的自动帧范围，可重新按计划切分。参考原音使用相同源时间范围裁切。

## 服务组成

| 层 | 实现 |
| --- | --- |
| 计划与校验 | `ShotContinuationPlanner`、`ShotContinuationService` |
| 后台素材准备 | `ShotContinuationPreparation`，复用参考视频准备线程池及源选择、下载限制、摘要归档 |
| 语义提示词 | `ShotContinuationAiService`，使用选中的 CHAT 模型，无 Java 关键词推断 |
| 串行执行与恢复 | `ShotContinuationRenderer`、现有 `VideoRenderBatchService` |
| 拼接 | `ShotContinuationMedia`，FFmpeg 精确裁重叠、24fps 合成、FFprobe 实际帧数校验 |
| 网关装配 | `h3_extensions.py`，按显式节点绑定插入可选模块 |
| ComfyUI H3 节点 | 官方 `MiniMaxH3AddGuide`、`MiniMaxH3FunControlNetApply`、`ModelPatchLoader`、`MiniMaxH3ContextWindows` |
| 界面 | `ShotContinuationEditor.vue` |

启动时 `ShotContinuationSchema` 以增量方式增加 `drama_shot.continuation_json`，创建 `shot_continuation_preparation` 与 `render_task_step`，缺失的执行身份列自动补充。需要正常的数据库 DDL 权限；升级失败会报错。没有改写历史任务 JSON。

SQL 保存每段输入摘要、执行 ID、真实节点/prompt ID、状态和归档地址。恢复时跳过已归档段，查询未确认的原执行；`UNKNOWN` 不会自动重复生成。明确失败的段可创建新执行，后续依赖串行推进。旧任务不能覆盖已有更新任务的镜头。取消停止后续步骤并尝试取消当前精确执行，无法证明停止时保留 `UNCONFIRMED`。

可选择复用同一镜头上次任务中输入未变的连续前缀。提示词、素材内容、种子、模型、帧范围或上游输出变化都会使相应段及后续段不再复用。最终成功只保存一个完整 Take，片段输出不会成为镜头当前视频。

## 安装及配置

### Union 2.0 模型与预处理器

使用 [Alibaba PAI Union 2.0](https://huggingface.co/alibaba-pai/MiniMax-H3-Fun-Controlnet-Union-2.0) 的 [Comfy-Org INT8 适配权重](https://huggingface.co/Comfy-Org/MiniMax-H3/tree/main/model_patches)：`minimax_h3_fun_controlnet_union_2.0_pruned_int8_convrot.safetensors`，约 4.53 GB，放在 `ComfyUI/models/model_patches/`。不要将 v1 文件改名冒充 v2。

需更新 ComfyUI 到支持 v2 的版本：原生 `ModelPatchLoader` 应识别 10 个控制块和权重元数据中的 `inpaint_masked_pixel_mode=post_norm`。模型结构识别、权重加载和控制应用均交给官方实现，不再增加私有检查节点。验收时记录模型摘要、ComfyUI 版本及真实加载结果，补丁清单只能证明文件名可选。

| 选项 | 实际处理 | 附加模型 |
| --- | --- | --- |
| Canny | 原生 `Canny` | 无 |
| Depth | `DepthAnythingV2Preprocessor` | `depth_anything_v2_vitl.pth` |
| HED | `HEDPreprocessor` | `ControlNetHED.pth` |
| MLSD | `M-LSDPreprocessor` | `mlsd_large_512_fp32.pth` |
| Pose | `DWPreprocessor`，显式指定 ONNX 检测器与姿态估计器 | `yolox_l.onnx`、`dw-ll_ucoco_384.onnx` |
| Scribble | `FakeScribblePreprocessor` | 复用 HED 权重 |
| Layout | 直接使用已制作的白底彩色目标框布局视频 | 当前不自动检测或跟踪目标 |
| Gray | 网关 FFmpeg 生成独立灰度控制副本，官方 Apply 接收该视频 | 无 |
| 已处理控制视频 | 跳过预处理，直接传递控制信号 | 按上传内容决定 |

八种控制由同一个 Union 2.0 权重提供，不是八个 ControlNet 模型。全部自动预处理需要额外五个文件，连同 Union 共六个；Scribble 复用 HED，Canny/Gray 不需要模型。Layout 的框、颜色及轨迹须提前制作，原始实拍视频不能直接当布局信号。

网关用 FFprobe 检查所选控制视频的真实解码帧数，必须覆盖本段真实输出范围；官方 Apply 可以补齐生成帧网格的技术尾部，不能用它掩盖真实源范围不足。Gray 使用单独的无颜色控制副本，普通语义参考保留原彩色视频与原音设置；源归档及摘要不改写。网关主机需要 FFmpeg 和 FFprobe，Docker 镜像已包含。

姿态、深度、HED、MLSD、Scribble 节点来自 [comfyui_controlnet_aux](https://github.com/Fannovel16/comfyui_controlnet_aux)，须安装到 ComfyUI 并使用实际运行 ComfyUI 的 Python 安装其依赖。下面是 Linux 远程主机的镜像下载命令；先改为实际 ComfyUI 路径。已有相同文件可跳过下载。若配置了 `AUX_ANNOTATOR_CKPTS_PATH`，将 `aux` 改为该目录。

```bash
cd /path/to/ComfyUI
aux="${AUX_ANNOTATOR_CKPTS_PATH:-custom_nodes/comfyui_controlnet_aux/ckpts}"
fetch() {
  mkdir -p "$(dirname "$2")"
  curl --fail --location --retry 3 --continue-at - --output "$2" "$1"
}
fetch 'https://hf-mirror.com/Comfy-Org/MiniMax-H3/resolve/main/model_patches/minimax_h3_fun_controlnet_union_2.0_pruned_int8_convrot.safetensors' \
  'models/model_patches/minimax_h3_fun_controlnet_union_2.0_pruned_int8_convrot.safetensors'
fetch 'https://hf-mirror.com/yzd-v/DWPose/resolve/main/yolox_l.onnx' \
  "$aux/yzd-v/DWPose/yolox_l.onnx"
fetch 'https://hf-mirror.com/yzd-v/DWPose/resolve/main/dw-ll_ucoco_384.onnx' \
  "$aux/yzd-v/DWPose/dw-ll_ucoco_384.onnx"
fetch 'https://hf-mirror.com/depth-anything/Depth-Anything-V2-Large/resolve/main/depth_anything_v2_vitl.pth' \
  "$aux/depth-anything/Depth-Anything-V2-Large/depth_anything_v2_vitl.pth"
fetch 'https://hf-mirror.com/lllyasviel/Annotators/resolve/main/ControlNetHED.pth' \
  "$aux/lllyasviel/Annotators/ControlNetHED.pth"
fetch 'https://hf-mirror.com/lllyasviel/Annotators/resolve/main/mlsd_large_512_fp32.pth' \
  "$aux/lllyasviel/Annotators/mlsd_large_512_fp32.pth"
```

### 更新部署和能力检查

默认节点、配置生成器及调试图均已切换 2.0。本地 `machine_local.yaml` 也已刷新，其余远程部署需同步更新。工作流变化后重新预览和准备，旧任务快照保持原有内容，不能把使用 v1 生成的片段视为 v2 缓存。先完成运行中的任务，再切换配置。

1. 更新 ComfyUI 到提供上述官方 H3 节点且支持 Union 2.0 加载的版本。保留现有 `ComfyUI-VideoHelperSuite` 视频读取与输出节点，按需安装 `comfyui_controlnet_aux`。基础原生 guide 路径不需要旧 `custom_nodes/freyja_h3`；新版 latent 接续和自定义二采使用独立的 `custom_nodes/freyja_h3_continuation`，必须保留并随应用更新。可在算力机的仓库副本运行 `python scripts/install_h3_continuation.py --comfy-root <ComfyUI目录>`（自动备份旧目录），或将最新节点 ZIP 的同名目录完整替换到 `custom_nodes/`，在队列空闲后重启 ComfyUI。此修改不会通过 HTTP 接口自动更新远端插件目录。
2. 按实际启用模块安装节点：窗口使用 `MiniMaxH3ContextWindows`，ControlNet 使用 `MiniMaxH3FunControlNetApply`、`ModelPatchLoader` 及 `models/model_patches/` 下的 Union 权重。可选姿态/深度预处理器须另行安装；Canny 使用原生节点。所选补丁必须与底模匹配。未启用的可选模块不成为基础 guide 接续的依赖；缺失模块会在能力中显示不可用。
3. 维护实际节点 YAML 中的 `h3_extensions`。默认模板已覆盖当前 11 个 H3 生成模型，保留各自 LoRA、采样步数与 FaceRefine 裁脸修复支路。可用以下命令从现有 API 图重新生成绑定：

   ```powershell
   python comfy_gateway/configure_h3_extensions.py comfy_gateway/nodes/machine_local.yaml --refresh
   ```

   此命令生成显式图绑定，不创建人工验收标记，不修改服务地址、凭证等其他字段。

4. 网关异步接续需要可写 Redis。媒体归档域名须在 `REFERENCE_MEDIA_HOSTS` 中允许，网关须能访问 Java 生成的归档运输地址。Java 运行环境需要 FFmpeg、FFprobe 和现有 MinIO 配置。
5. 参考预算独立配置。Java 默认单参考片段最长 15 秒、360 帧；362 帧的生成段约 15.083 秒。可采用 345 帧段长保持默认参考预算；若要用满 362 帧的 ControlNet，先实测，再将 Java `freyja.reference-video.max-clip-seconds` 至少设为 16、`max-decoded-frames` 至少设为 362，并在网关 `reference_video` 中声明实测的每段/总时长、帧数及像素预算。不要只扩大一侧配置。
6. 先做只读探测：

   ```powershell
   python comfy_gateway/probe_h3.py --node-config comfy_gateway/nodes/machine_local.yaml --model minimax-h3-ref-turbo8
   ```

   输出 `controlVersion`、`backendSignature`、缺失节点、支持的控制预处理器和 `workflowRevision`。探测本身不生成视频。
7. 重新读取当前能力并预览计划。参考视频使用网关实时媒体限制；模型中心可以设置更小的限制，无需重复录入能力 JSON 或设置验收状态。准备快照仍绑定实际工作流版本与节点，过期输入不能继续提交。

原生路径探测不再要求任何项目私有节点。能力的 `controlTypes` 只列出已安装预处理节点支持的类型，以及网关准备的 Gray 和显式预处理视频类型。`contextWindowsWithControlNet` 单独声明原生联用验收范围，节点可见不会自动开启该组合。HED / MLSD / Pose / Depth / Scribble 缺失时，安装 `comfyui_controlnet_aux` 及依赖、对应预处理模型后重启；不影响 Canny、Gray 或已处理视频的节点可用性。

历史片段二采另需在 `/object_info` 确认 `FreyjaH3LoadLatent` 已注册。若其余 FREYJA 节点存在但它缺失，表示算力节点仍加载旧包；更新 `freyja_h3_continuation` 并重启后再重跑，无需重新生成一采视频。该缺失与放大模型权重无关，动态增强的完整依赖见 [安装与验证边界](video-enhancements.md#安装与验证边界)。

新任务的二采段间 latent 接续还需 `FreyjaH3UpscaleContinue`，并更新 `FreyjaH3SaveLatent` 以写入 `SECOND_PASS` 阶段。后端启动时新增 `dynamic_workflow_step.result_manifest_json` 保存二采归档清单；网关与后端须一同更新。旧任务快照不自动切换模式；缺少前驱二采 latent 的历史视频，可保留一采，从首段连续重跑二采以建立新链路。

运行时探测检查已安装节点与所选补丁是否在列表中；诊断指纹反映节点 schema 与版本，计划 revision 绑定 API 图、网关装配代码、媒体准备代码和模块配置。无需保存诊断指纹作为人工验收标记；工作流或素材变化仍需重新准备快照。

## 调试工作流与 GPU 验收

导入 `comfyUI_workflow/minimax_h3_continuation_control_debug.json`。它是现有 Turbo8 工作流的 UI 调试伴随图，默认约 5 秒，**不是整条长片的手工拼接器**。

- 节点 200：上下文窗口，默认旁路，Ctrl+B 启用。
- 节点 201：官方可选 ControlNet Apply，默认旁路；先给原参考视频节点加载控制源。与节点 200 联用须先单独验收。
- 节点 202–203：官方补丁加载器和 Canny；示例生成 124 帧，网关真实要求 120 帧，仅技术尾部允许末帧填充。手工运行调试图时自行确认源帧数覆盖，因为不会经过网关检查。
- 节点 205：官方音画 Guide，首段旁路，后续段启用；仅输出条件，采样 latent 保持原接线。
- 节点 206：上一段实际尾部上下文，默认禁用；上传 39 帧上下文后 Ctrl+M 启用。同步设置生成帧数、真实输出帧数与上下文范围。

调试图的节点、输入输出插槽与双向连线已静态验证，仅包含官方 H3 节点及原工作流的已有依赖。导入时若缺节点，应先更新 ComfyUI 或安装原工作流依赖；不能据此跳过节点验收。

逐模板验证：接续开/关 × 有/无参考视频 × ControlNet 开/关 × 窗口开/关，测试官方 Guide 的视频和音频、横竖尺寸、最后一段及 FaceRefine 修复支路。窗口与 ControlNet 组合在验收前应被阻止；单独验收时使用非周期控制动作，检查后续窗口没有重播控制视频开头。先短片，再 30 秒、60 秒；记录显存、采样/解码时长、模型与节点版本、参考上限。

模拟响应丢失、Redis/SQL 写入失败、归档中断、重启恢复与取消。未确认提交且未保存真实 prompt 时系统会停止自动重试，需人工核对原节点 queue/history；目前没有自动按 `executionId` 扫描上游历史的调解工具。

检查实际解码帧数、PTS、尺寸、完整解码和音画时长，并正常速度查看每个接缝前后约 1 秒的动作、手脸、背景运动、重复对白和停顿。帧数正确不能证明无缝。

## 接口

以下均使用 `/drama/shot/{shotId}/continuation` 前缀，所有业务 ID 在前端保持字符串：

| 方法与后缀 | 用途 |
| --- | --- |
| `GET`、`PUT` | 读取 / 保存草稿及镜头时长 |
| `GET /presets` | 返回段长档位及对应帧数、默认重叠 |
| `POST /split-reference` | `{draft, referenceVideo, targetSegmentIds?}`，探测源视频并返回含明确切片绑定的计划；无需 GPU 和已验收视频模型 |
| `POST /preview` | `{draft, auto, providerId, modelCode}`，返回段数、范围、重叠、`planHash`、`renderable` 与原因 |
| `POST /plan-task` | 同一草稿与 CHAT 提供商/模型，返回持久化 AI 任务 ID |
| `POST /prepare` | `{providerId, modelCode, size, planHash}`，启动后台素材准备 |
| `GET /preparation/{id}` | 查询 `PREPARING / READY / FAILED` |

准备完成后复用 `POST /drama/shot/{shotId}/render`，提交提供商、工作流、尺寸和 `expectedRenderFingerprint=planHash`。内部快照字段禁止由客户端提交。

Java 为每个执行段调用现有视频生成端点，使用 `X-Freyja-Async-Submit: true` 返回真实执行身份；随后按 `/v1/tasks/{executionId}` 查询。已持久化 `prompt_id` 与 `node_id` 可一起传入查询及输出接口，避免只依赖 Redis TTL。异步提交相同执行 ID 只能对应相同输入摘要。

## 当前实现边界

- `minimax-h3-ref2va` 的 16GB HiFi 底模与当前 Union 2.0 ControlNet 权重的 AdaLN 结构不匹配（底模宽度 16，控制权重宽度 8）。该模型在节点 YAML 中显式配置 `control_net: false` 和 `control_net_unavailable_reason`；能力接口返回 `h3.controlNet=false`、空 `controlTypes` 及 `controlNetUnavailableReason`。单镜、批量和接续预检在入队前拒绝所有启用的 ControlNet 类型（包括已预处理视频），网关在提交算力节点前再次拒绝，不自动关闭或忽略用户的控制设置。普通语义参考视频、接续与模板中的 Sol Attention、Fused Modulation、Chunk FeedForward、EasyCache 仍保留。其他官方 Ref2VA 模型沿用各自的能力配置。部署时同步更新后端与网关，并同步本地覆盖文件 `nodes/machine_local.yaml`；旧预检需重新生成，未来使用匹配权重并完成验证后再调整此组合配置。
- 手动范围遵守当前 H3 的 `17k+5` 帧数、17 的倍数起点与真实重叠约束。300 秒 / 最多 100 段是产品保护上限。
- 采用固定重叠裁切与 24fps 合成；尚未实现自动找最佳动作切点、几何/亮度修复、局部补帧或接缝视频预览。
- 保留各段生成音频并按相同范围裁切；无音轨时补静音。尚未提供源母带、外部母带和静音策略切换，未自动消除对白重复。
- 长源按各绑定独立下载、归档与处理，尚无一次标准化后全源共享的母带缓存。源时长、文件体积、像素和模型参考预算仍生效。
- 已完成本地图装配、SQL 恢复、素材隔离、前端构建和 FFmpeg 机械检查；真实 GPU 的运动连续性、控制时间正确性与音画质量待验收。

## 本地验证记录

2026-11-06 官方节点迁移：

- `python -m unittest discover -s . -p 'test_*.py' -q`：96 项通过。包含官方 Guide 条件接线与原采样 latent 保持不变、全部控制类型路由、缺帧拒绝、未验收窗口联用拒绝、旧锁定执行快照拒绝、异步提交幂等。
- 真实 FFmpeg 检查：Gray 控制副本与普通彩色语义参考分别上传，保留 24fps 和全部实际帧，灰度 RGB 通道一致，普通参考保留颜色；原归档内容与摘要不改写，无音轨时提供 VHS 兼容静音轨。
- JDK 26 + Maven wrapper：26 项接续计划、素材隔离、源切片、旧草稿读取、窗口联用能力校验、执行恢复和真实 FFmpeg 合成回归通过。
- `npm run build`：Vue TypeScript 校验及 Vite 生产构建通过；存在已有构建体积提示。
- 11 个 H3 模板的非 H3 配置保持一致，本地节点配置同步刷新。UI 调试图包含 37 个节点、46 条连接，双向连接一致且没有项目自定义节点。
- 远端只读探测：官方 Guide、ControlNet Apply、补丁加载器、窗口及 Union 2.0 文件名可用；缺第三方 DWPose / DepthAnythingV2 / HED / MLSD / FakeScribble 节点。`controlNet=true` 表示节点与文件名存在，`status=UNVERIFIED` 及 `contextWindowsWithControlNet=false` 保留；真实 GPU 接缝、预处理和控制效果仍待验收。
