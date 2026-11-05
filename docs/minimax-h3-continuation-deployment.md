# MiniMax H3 接续部署与验收

2026-11-05。代码已接入；目标 ComfyUI 连接超时，尚未提交真实 GPU 验收任务。配置保持 `UNVERIFIED`，接续渲染和 ControlNet 组合在验收通过前不可提交。普通短视频入口继续使用原有能力规则。

## 使用入口与实际行为

在分镜抽屉或渲染工坊打开「接续模式与片段计划」。接续设置属于单镜头，缺省关闭；关闭时生成请求最多 15 秒，超过 15 秒的旧草稿保留但不可提交。开启后可以设置长时长，产品计划上限为 300 秒；上限不代表目标硬件已能完成对应画质。

1. 选择与镜头生成模式一致的视频提供商、模型和尺寸，开启接续。
2. 自动拆分，或手动增加/编辑片段。自动计划可以继续手动修改。编辑的是生成帧范围，服务端根据真实重叠计算成片保留范围；目前没有任意成片切点编辑。
3. 每段填写提示词，或选择已启用的 CHAT 模型生成分段提示词。文本规划使用持久化 AI 任务及统一事件日志，系统提示词可通过 `ai.prompt.shot-continuation-plan` 配置覆盖。
4. 明确给片段挂载图片和视频。镜头原素材只进入待分配托盘；未挂载的素材不参与生成。首尾帧工作流也必须给片段明确设置首尾帧，原镜头首尾帧不会隐式带入。尾帧若落在最终裁掉的补齐区会阻止提交。
5. 长参考视频可上传或从分镜 / 历史 Take 选择到待分配素材，设置源范围后「按时间轴分配」到所选片段。默认源起点对应镜头 0 秒，播放速度 1:1，结果写为各段可编辑的独立绑定。新增片段不自动继承。
6. 有参考视频的片段才显示可选 ControlNet，默认关闭。多视频时选择控制源；其他视频仍可作为语义参考。删除所有视频关闭该段 ControlNet，不关闭接续。
7. 检查段数、生成范围、共享帧、成片保留范围、素材源范围和不可用原因。点击渲染后先在后台冻结/归档素材，再进入已有串行视频队列。

参考生成工作流支持普通图片与视频参考；首尾帧工作流支持片段显式首尾帧，不支持 Ref2VA 视频引用。上传和素材选择不要求逐项用途说明；本段提示词按本段顺序引用 `<Picture N>` / `<Video N>`。原音是否参与条件由片段视频的「参考原音」设置控制。

上一段真实生成尾部由系统作为技术上下文传递，不占普通引用编号。默认使用原生 `MiniMaxH3AddGuide`；开启锁定共享上下文后使用项目提供的 AV latent/mask 扩展，两者均须真实采样验收。

## 服务组成

| 层 | 实现 |
| --- | --- |
| 计划与校验 | `ShotContinuationPlanner`、`ShotContinuationService` |
| 后台素材准备 | `ShotContinuationPreparation`，复用参考视频准备线程池及源选择、下载限制、摘要归档 |
| 语义提示词 | `ShotContinuationAiService`，使用选中的 CHAT 模型，无 Java 关键词推断 |
| 串行执行与恢复 | `ShotContinuationRenderer`、现有 `VideoRenderBatchService` |
| 拼接 | `ShotContinuationMedia`，FFmpeg 精确裁重叠、24fps 合成、FFprobe 实际帧数校验 |
| 网关装配 | `h3_extensions.py`，按显式节点绑定插入可选模块 |
| ComfyUI 扩展 | `comfy_gateway/comfy_extension/freyja_h3/` |
| 界面 | `ShotContinuationEditor.vue` |

启动时 `ShotContinuationSchema` 以增量方式增加 `drama_shot.continuation_json`，创建 `shot_continuation_preparation` 与 `render_task_step`，缺失的执行身份列自动补充。需要正常的数据库 DDL 权限；升级失败会报错。没有改写历史任务 JSON。

SQL 保存每段输入摘要、执行 ID、真实节点/prompt ID、状态和归档地址。恢复时跳过已归档段，查询未确认的原执行；`UNKNOWN` 不会自动重复生成。明确失败的段可创建新执行，后续依赖串行推进。旧任务不能覆盖已有更新任务的镜头。取消停止后续步骤并尝试取消当前精确执行，无法证明停止时保留 `UNCONFIRMED`。

可选择复用同一镜头上次任务中输入未变的连续前缀。提示词、素材内容、种子、模型、帧范围或上游输出变化都会使相应段及后续段不再复用。最终成功只保存一个完整 Take，片段输出不会成为镜头当前视频。

## 安装及配置

1. 将 `comfy_gateway/comfy_extension/freyja_h3` 整个目录复制到实际算力主机的 `ComfyUI/custom_nodes/freyja_h3`，重启 ComfyUI。该扩展依赖当前 H3 原生实现，包括 `FRAME_PER_TOKEN`、`MiniMaxH3AddGuide` 与 `MiniMaxH3FunControlPatch`，升级后需重新验证。
2. 按实际启用模块安装节点：窗口使用 `MiniMaxH3ContextWindows`，ControlNet 使用 `MiniMaxH3FunControlNetApply`、`ModelPatchLoader` 及 `models/model_patches/` 下的 Union 权重。可选姿态/深度预处理器须另行安装；Canny 使用原生节点。所选补丁必须与底模匹配。未启用的可选模块不成为基础 guide 接续的依赖；缺失模块会在能力中显示不可用。
3. 维护实际节点 YAML 中的 `h3_extensions`。默认模板已覆盖当前 11 个 H3 生成模型，保留各自 LoRA、采样步数与 FaceRefine 裁脸修复支路。可用以下命令从现有 API 图重新生成绑定：

   ```powershell
   python comfy_gateway/configure_h3_extensions.py comfy_gateway/nodes/machine_local.yaml --refresh
   ```

   此命令把组合状态重置为 `UNVERIFIED`，不修改服务地址、凭证等其他字段。执行前保留验收记录。

4. 网关异步接续需要可写 Redis。媒体归档域名须在 `REFERENCE_MEDIA_HOSTS` 中允许，网关须能访问 Java 生成的归档运输地址。Java 运行环境需要 FFmpeg、FFprobe 和现有 MinIO 配置。
5. 参考预算独立配置。Java 默认单参考片段最长 15 秒、360 帧；362 帧的生成段约 15.083 秒。可采用 345 帧段长保持默认参考预算；若要用满 362 帧的 ControlNet，先实测，再将 Java `freyja.reference-video.max-clip-seconds` 至少设为 16、`max-decoded-frames` 至少设为 362，并在网关 `reference_video` 中声明实测的每段/总时长、帧数及像素预算。不要只扩大一侧配置。
6. 先做只读探测：

   ```powershell
   python comfy_gateway/probe_h3.py --node-config comfy_gateway/nodes/machine_local.yaml --model minimax-h3-ref-turbo8
   ```

   输出 `backendSignature`、缺失节点、支持的控制预处理器和 `workflowRevision`。探测本身不生成视频，也不把状态改为已验收。
7. 在目标 GPU 上完成下述验收后，将对应模型 `h3_extensions.status` 设为 `VERIFIED`，并保存本次 `backendSignature` 到 `verified_backend_signature`。随后重新读取能力、预览计划。引用视频还须独立满足既有 `reference_video` 的 VERIFIED、工作流 revision 与 backend 规则。

运行时签名检查已安装节点输入 schema、报告的 ComfyUI/PyTorch/Python 版本、后端地址和所选补丁是否在列表中；计划 revision 包含 API 图、网关装配代码和模块配置。签名不读取远端模型文件内容，不能替代模型摘要、扩展部署版本及真实渲染验收记录。

## 调试工作流与 GPU 验收

导入 `comfyUI_workflow/minimax_h3_continuation_control_debug.json`。它是现有 Turbo8 工作流的 UI 调试伴随图，默认约 5 秒，**不是整条长片的手工拼接器**。

- 节点 200：上下文窗口，默认旁路，Ctrl+B 启用。
- 节点 201：窗口对齐的可选 ControlNet，默认旁路；先给原参考视频节点加载控制源。
- 节点 202–204：补丁、Canny 和控制覆盖检查；示例生成 124 帧，真实要求 120 帧，仅技术尾部允许末帧填充。
- 节点 205：锁定 AV 上下文，首段旁路，后续段启用。
- 节点 206：上一段实际尾部上下文，默认禁用；上传 39 帧上下文后 Ctrl+M 启用。同步设置生成帧数、真实输出帧数与上下文范围。

调试图的节点、输入输出插槽与双向连线已静态验证。导入时若缺原生节点或扩展节点，应先完成安装；不能据此跳过节点验收。

逐模板验证：接续开/关 × 有/无参考视频 × ControlNet 开/关 × 窗口开/关，另测 guide 与锁定 AV、横竖尺寸、最后一段及 FaceRefine 修复支路。使用非周期控制动作，检查第三个及后续窗口没有重播控制视频开头。先短片，再 30 秒、60 秒；记录显存、采样/解码时长、模型与扩展版本、参考上限。

模拟响应丢失、Redis/SQL 写入失败、归档中断、重启恢复与取消。未确认提交且未保存真实 prompt 时系统会停止自动重试，需人工核对原节点 queue/history；目前没有自动按 `executionId` 扫描上游历史的调解工具。

检查实际解码帧数、PTS、尺寸、完整解码和音画时长，并正常速度查看每个接缝前后约 1 秒的动作、手脸、背景运动、重复对白和停顿。帧数正确不能证明无缝。

## 接口

以下均使用 `/drama/shot/{shotId}/continuation` 前缀，所有业务 ID 在前端保持字符串：

| 方法与后缀 | 用途 |
| --- | --- |
| `GET`、`PUT` | 读取 / 保存草稿及镜头时长 |
| `POST /preview` | `{draft, auto, providerId, modelCode}`，返回段数、范围、重叠、`planHash`、`renderable` 与原因 |
| `POST /plan-task` | 同一草稿与 CHAT 提供商/模型，返回持久化 AI 任务 ID |
| `POST /prepare` | `{providerId, modelCode, size, planHash}`，启动后台素材准备 |
| `GET /preparation/{id}` | 查询 `PREPARING / READY / FAILED` |

准备完成后复用 `POST /drama/shot/{shotId}/render`，提交提供商、工作流、尺寸和 `expectedRenderFingerprint=planHash`。内部快照字段禁止由客户端提交。

Java 为每个执行段调用现有视频生成端点，使用 `X-Freyja-Async-Submit: true` 返回真实执行身份；随后按 `/v1/tasks/{executionId}` 查询。已持久化 `prompt_id` 与 `node_id` 可一起传入查询及输出接口，避免只依赖 Redis TTL。异步提交相同执行 ID 只能对应相同输入摘要。

## 当前实现边界

- 手动范围遵守当前 H3 的 `17k+5` 帧数、17 的倍数起点与真实重叠约束。300 秒 / 最多 100 段是产品保护上限。
- 采用固定重叠裁切与 24fps 合成；尚未实现自动找最佳动作切点、几何/亮度修复、局部补帧或接缝视频预览。
- 保留各段生成音频并按相同范围裁切；无音轨时补静音。尚未提供源母带、外部母带和静音策略切换，未自动消除对白重复。
- 长源按各绑定独立下载、归档与处理，尚无一次标准化后全源共享的母带缓存。源时长、文件体积、像素和模型参考预算仍生效。
- 已完成本地图装配、SQL 恢复、素材隔离、前端构建和 FFmpeg 机械检查；真实 GPU 的运动连续性、控制时间正确性与音画质量待验收。

## 本地验证记录

- JDK 26 + Maven wrapper：146 项受影响 Java 回归测试通过，覆盖视频提交、镜头时长、素材准备、SQL 恢复、取消、Take、片段素材隔离和真实 FFmpeg 合成。
- `python -m unittest discover -s . -p 'test_*.py' -q`：89 项网关测试通过，覆盖可选模块、严格帧数、图连接、异步提交幂等和未启用 ControlNet 时的独立能力检查。
- `npm run build`：Vue TypeScript 校验及 Vite 生产构建通过；存在已有构建体积提示。
- 60 秒测试视频实际合成 1440 帧，可完整解码；从生成段提取的上下文为 39 帧。
- UI 调试图包含 38 个节点、47 条连接，节点插槽及双向连接一致性通过静态检查。
