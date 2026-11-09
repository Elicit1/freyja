# MiniMax H3 多模式接续设计

日期：2026-11-09。状态：四种模式已完成代码接入与离线验证；算力节点离线，节点安装、线上部署与实际视频效果尚未验证。本次不提交视频生成任务。

本文补充现有接续设计；涉及多模式、能力发现及验收标记的约定以本文为准。其余镜头、素材、任务中心和 Take 历史架构继续复用。

## 1. 目标与当前基础

让创作者在同一条接续时间轴中选择不同的接续方式，在动作衔接、画质维持与环境依赖之间做明确选择。模式不承诺绝对消除劣化。

当前实现：

- `ShotContinuation` 保存段长、重叠、片段内容与参考素材，没有接续策略字段。
- `ShotContinuationPlanner` 计算连续重叠时间轴；实际重叠以相邻片段范围为准。
- `ShotContinuationRenderer` 串行生成，将上一段 MP4 尾部交给下一段。
- `comfy_gateway/h3_extensions.py` 接入 `MiniMaxH3AddGuide`，只连接 conditioning 输出，没有原始 AV latent 交接与重绘遮罩。
- 已有预览指纹、异步素材准备、逐段 SQL 状态、恢复和历史任务复用。

继续采用「一个分镜 → 多个内部执行段 → 一个成片 Take」，不为每种接续模式建立独立任务系统。

## 2. 四种用户模式

| 显示名称 | 协议值 | 实现方式 | 主要用途 | 新增条件 |
| --- | --- | --- | --- | --- |
| 标准引导 | `GUIDE` | 干净尾部画面 → AddGuide 条件引导 | 保持现有行为、较短接续 | 现有官方引导节点 |
| 彩噪引导 | `GUIDE_CHROMA` | 尾部画面临时副本注入渐弱彩噪 → AddGuide | 希望缓解逐段变糊，优先复用现有链路 | 明确版本的画面注噪节点及对应适配 |
| 引导＋重绘 | `LATENT_REDRAW` | 原始 AV latent 尾部 → 新采样前缀 → 渐变遮罩、逐步 remask | 希望保留动作上下文并允许其受控重绘 | latent 读写、H3 时序映射、采样遮罩适配 |
| 抗劣化接续 | `LATENT_PROTECTED` | latent 上下文随采样加噪、接缝渐弱 + 干净来源帧锚定 | 同一场景的较长连续镜头 | 前述 latent 能力、上下文噪声、锚定及延迟注入适配 |

四种模式按不同执行器处理。`LATENT_PROTECTED` 不再额外叠加 Director 重绘或画面彩噪。用户不能同时打开数个互相争用前缀的开关。

第一段没有上一段上下文，不执行接续策略；但必须根据后续所需能力保存 latent 和锚点。四种模式均不默认增加一轮完整视频重采样；抗劣化模式的额外锚点 token、latent 传输与存储会增加开销，不承诺相同耗时。

旧项目、旧快照缺省为 `GUIDE`。新增模式在部署前显示“待接入”，部署后依据真实能力提供选择；未经实际视频验证不称作画质保证。

## 3. 工作台交互

在接续总控加入「默认接续方式」四选一卡片，简述作用及依赖。模式与段长、重叠分开设置，改变模式不自动重建时间轴或清空素材。

片段编辑器增加「与上一段衔接」：默认继承全局，也可以覆盖模式及该模式参数。首段显示「起始段」，仅配置后续需要的归档与锚点。

参数按模式展示：

| 模式 | 普通设置 | 高级设置与初始值 |
| --- | --- | --- |
| 标准引导 | 无额外参数 | 继续使用当前共享上下文帧数 |
| 彩噪引导 | 彩噪强度、接缝保留强度 | 注噪方式固定为有版本的配方；研究起点为 0.45 → 0.10、末 3 帧渐弱 |
| 引导＋重绘 | 重绘幅度 | 研究起点 0.10；不等同于整段 denoise |
| 抗劣化接续 | 上下文噪声强度、干净来源段 | 噪声强度 1.0、线性渐弱；末端为 0；保留来源首帧；尾部锚点默认关闭，可选在采样后 25% 注入 |

这些值是上游研究起点，不是 FREYJA 已验证预设。彩噪强度依赖具体混合公式与色彩空间；必须和配方版本一起保存，不能把不同插件的同名数值直接互换。重叠不足时禁止生成无意义渐弱区，预览返回明确参数冲突，不暗改数值。

尾部锚点可能影响姿态，适合作为高级选项；当前水袖任务应先维持动作参考和 POSE 控制，避免为了画质改变动作。

「锁定自动规划」只控制 AI 是否改写片段内容；不表示采样时锁定上下文。已有锁定片段也不得被 AI 擅自更改接续方式或参数，创作者仍可手动编辑。

时间轴标出每个接续点的生效模式。预览显示：真实输出时长、上下文来源、需要重做的起始段、可复用范围，以及能力缺失原因。能力错误聚合展示，不逐段重复相同提示。

## 4. 草稿与计划协议

草稿升级到 `schemaVersion=2`，示意：

```json
{
  "schemaVersion": 2,
  "enabled": true,
  "durationSeconds": 30,
  "segmentFrames": 124,
  "overlapFrames": 22,
  "continuity": {
    "mode": "GUIDE_CHROMA",
    "chroma": {
      "recipe": "CHROMA_TAPER_V1",
      "strength": 0.45,
      "seamStrength": 0.10,
      "taperFrames": 3
    }
  },
  "segments": [
    { "id": "segment-a", "startFrame": 0, "endFrame": 124 },
    {
      "id": "segment-b",
      "startFrame": 102,
      "endFrame": 226,
      "continuityOverride": {
        "mode": "LATENT_REDRAW",
        "redraw": { "recipe": "H3_PREFIX_REDRAW_V1", "strength": 0.10 }
      }
    }
  ]
}
```

示例省略现有剧本、素材与后续片段，仅表示新增字段。`continuityOverride=null` 表示完整继承全局设置，避免部分字段层层合并产生歧义。只允许当前模式对应的参数块；参数由各自严格 DTO/schema 校验。

抗劣化参数：`recipe`、`noiseStrength`、`fade`、`anchorSourceSegmentId`、`useFirstFrameAnchor`、`useTailAnchor`、`tailAnchorStartFraction`。默认来源为本任务首段；其他来源必须显式指定已完成前序段或授权历史产物。不得由 Java 根据剧本文字猜测换景并自动改来源。

预览计划每个 range 增加：

- `effectiveContinuity`：解析继承后、带配方版本的生效设置。
- `contextRequirement`：需要 MP4/像素或 AV latent、帧范围与预期格式。
- `requiredArtifacts`：该段需要归档的产物，包括后继段所需 latent、锚点。
- `frameMapping`：源帧范围、有效输出范围、前后裁剪与 VAE 相位信息。
- `reuseStatus`、`recomputeFromSegmentId`：可复用性及变更影响。

`planHash` 包含生效设置、素材摘要、输出规格、模型/LoRA/VAE与调度配置标识、节点与适配版本、帧映射、锚点来源。关闭模式的未使用 UI 参数不进入执行指纹。

## 5. 网关能力与传输

复用 `/models/{modelCode}/capabilities` 和工作台 `/continuation/options`，增加能力对象：

```json
{
  "continuationModes": [
    { "id": "GUIDE", "available": true },
    {
      "id": "LATENT_PROTECTED",
      "available": false,
      "missingRequirements": ["H3_CONTEXT_NOISE_ADAPTER"]
    }
  ],
  "continuationAdapterRevision": "sha256:..."
}
```

每个模式还返回参数范围、配方版本、支持的底模任务类型及 ControlNet/片段内窗口组合。以明确模板绑定、实际节点 schema、权重可用性和适配实现支持为依据。相同请求共享能力快照，避免每段查询整个节点清单。

不设置 `UNVERIFIED/VERIFIED` 或人工验收开关。缺少真实实现、节点或权重才阻止相应模式；返回具体缺项。社区实验结果与推荐值单独作为说明，不变成人工审批条件。节点可见也不等同于任意采样 wrapper 都能兼容，组合规则必须有明确适配实现依据。

`h3_options` 升级为 v2，新增 `continuity` 和强类型上下文输入；v1 继续走原有标准引导适配。Java 保存运行配置，网关决定具体图接线，Java 不直接拼插件节点。

上下文使用判别联合：`PIXELS` 输入归档对象摘要和帧映射，`AV_LATENT` 输入受管 artifact ID、摘要及 manifest。输入不再强制所有模式都带 `video_url`。第一段允许无上下文，但可要求输出 artifact。

内部模式不能消耗用户的普通参考视频数量配额；图片锚定与普通图片引用也分开计数。内部上下文仍受模型 token、帧数、显存等真实预算约束。各段参考视频数量/时长按各段计算，不累计限制整个长镜头。

## 6. 四条执行路径

### 6.1 标准引导

保留现有干净 MP4 尾部 → AddGuide。内部媒体可逐步改为无损上下文归档，避免重复有损编码；不改用户已生成的成片。

### 6.2 彩噪引导

干净尾部 → 解码至画面 tensor → 版本化色度注噪和时间渐弱 → AddGuide → 原有 sampler。

注噪必须发生在 ComfyUI 的临时图像分支，避免彩噪经 H.264/AAC 编码后改变配方强度。实现 `CHROMA_TAPER_V1` 前固定上游算法来源、色彩空间、混合公式、噪声分布、取值范围和裁剪规则；无法精确复现时建立自己的配方版本，不沿用上游参数含义。

随机种子由父任务 seed、稳定 segment ID、配方版本确定，重试复现同一噪声。只污染交接副本，不污染用户动作参考、POSE/Depth 信号、普通角色/场景图或音频；也不把临时注噪素材作为下一轮归档源。

### 6.3 引导＋重绘

读取上一段原始采样 AV latent → 按 H3 VAE 时序网格选取尾部 → 写入下一段采样 latent 前缀 → 构造渐变 noise mask → 安装匹配调度的逐步 remask → 采样、解码、保存。

这是一个独立执行路径，不再对同一上下文叠加 AddGuide cond-pin。用户角色/场景引用仍经原有 conditioning 输入。必须保留底模、加速 LoRA、sigma shift 与调度器约定，不能把整段 denoise 改为 0.10。

### 6.4 抗劣化接续

以原始 latent 尾部构建上下文；给较早的上下文分配采样噪声，向接缝渐弱至 0。采样器混合与 H3 局部 timestep 标签使用一致遮罩。

额外从明确来源提供干净首帧锚点；可选尾部锚点晚期注入，降低对动作姿态的牵引。用于锚定的临时槽位及恢复操作由适配器处理，不能让来源段画面插入最终时间轴。

实际接续上下文在输出和保存交接 latent 前恢复为原始值；锚点和重叠的裁剪必须采用该执行器自己的 frameMapping，不照搬现有裁前缀方案或某个插件的裁尾方案。

该模式的“强度 1.0”代表配方定义的上下文噪声权重，不是给整个输出叠加 100% 彩噪。音频不跟随视觉注噪设置改变，沿用独立音频策略。

## 7. latent 归档、恢复与历史复用

复用现有任务及 `render_task_step`，追加 `result_manifest_json` 和 `result_schema_version`；产物在受管存储中归档，数据库只保存受管引用及摘要。

产物包括：干净视频、必要的原始视频/音频 latent、用于交接的帧映射、来源锚点、模型/VAE/LoRA及适配版本标识。初版可保存完整 AV latent 以保证正确性；后续按下游最大所需重叠加相位余量裁成尾部包，不能固定只存 22 帧。

使用 safetensors 与 JSON manifest，不加载任意来源的 pickle。artifact 访问复用当前项目授权和服务间媒体授权。归档后才可跨节点重载；只留在某个 GPU 节点内存或临时目录的缓存不能作为恢复依据。

manifest 必须区分“正常采样内容”“恢复后的交接内容”和“临时注噪/锚定槽”，并记录画布尺寸、帧数、AV 布局、VAE 时间网格与导出裁剪。需要二采的模板分别标识一采/最终 latent；选择兼容画布必须写入计划，不临时静默切换。

阶段状态继续持久化，补充必要的归档阶段：模型已完成但 artifact 上传失败时，从原 prompt/output 恢复归档，不自动重新消耗 GPU。该段对后继有必需 artifact 时，视频和 artifact 都持久化成功才可标记步骤成功。失败时保留已完成视频，明确缺少哪项交接产物。

更改第 N 段策略，一般影响第 N 段及其后继；若新模式需要此前未保存的 latent，预览明确显示需要重生成的前驱。用户可选择重新生成前驱，或显式改用支持 MP4 的模式；不静默降级、不冒充原始 latent。任何可选“从视频重编码 latent”的能力应作为独立来源类型和指纹记录，不能伪装成原始交接。

历史复用需匹配视频输入、模式配方、实际前驱 artifact 内容摘要与锚点来源。URL 签名变化不使内容相同的 artifact 失效；同一 URL 的内容变化必须使指纹变化。跨节点复用还需验证模型与适配兼容性。

新增兼容转换集中在明确的 `ContinuationConfigMigration`（拟新增），不散落在执行器；旧草稿保持可读，旧不可变任务按原版本解释，不把历史快照原地升级成新模式。

## 8. 时间轴、动作参考与音频

继续固定 24 fps，生成帧数与共享上下文遵循当前 H3 `17k+5` 网格和起点约束。生成长度包含上下文；UI 同时显示真实新增长度，例如 124 帧生成、22 帧重叠的普通续段新增 102 帧，约 4.25 秒。

不同模式采用统一业务时间轴，但允许不同内部映射。原始 latent 尾部不能仅按普通 tensor 下标截取，必须按 VAE 相位对齐。需要移动切点或减少前段尾部时，把变化显式写入计划并重新计算动作参考切片、ControlNet 范围、音频区间与输出裁剪；必要时重新生成前驱，使总成片帧数精确一致。不能运行到一半静默改映射。

角色/场景图负责身份与环境；参考视频负责内容或动作；POSE/Depth 等逐帧控制负责显式结构约束；接续上下文负责边界连续性。四者独立，注噪只处理接续副本。

已有 ControlNet 自动提取按当前片段映射执行，特征缓存键包含源内容、源范围、24 fps 策略、预处理器版本和参数。更换视觉接续模式、未改变动作源范围时，允许复用已有特征，不重复提取。

音频初版保留现有行为，不新增一套混音系统；视觉噪声参数永远不传入音频加噪分支。latent 交接仍需完整保存音频布局及对应时间映射。后续若开放音频重绘，另设独立参数。

「片段内上下文窗口」继续作为独立计算选项，不是第五种接续策略。不同模式与窗口/ControlNet 的组合由对应适配能力决定，不能仅凭三个独立节点存在就声称可组合。

## 9. 代码改动位置

| 层 | 改动 |
| --- | --- |
| Java DTO | `ShotContinuation` 新增策略及片段覆盖；`ShotContinuationPlan` 增加生效策略、产物依赖、映射和复用影响 |
| Java 规划 | `ShotContinuationPlanner` 保留纯帧计算；新增策略解析/校验组件；`ShotContinuationService` 绑定能力与指纹 |
| Java 执行 | `ShotContinuationRenderer` 使用统一结果对象处理视频与 artifact；`ShotContinuationMedia` 按映射合成 |
| 数据库 | `ShotContinuationSchema` 增量增加步骤结果 manifest 字段，迁移幂等；状态恢复同步支持归档阶段 |
| 网关 | `h3_extensions.py` 演进为严格协议入口；拟新增 `h3_continuation/`，按四种执行器独立构图 |
| ComfyUI | 提供明确版本的注噪、latent 导入导出、重绘、抗劣化适配；可包装上游节点，不依赖 Director 整套 UI |
| 前端 | `types/continuation.ts`、`ShotContinuationEditor.vue`、`ContinuationSegmentEditor.vue`、计划预览及执行进度 |
| MCP/Skill | 在现有已发布接续工具内扩展参数与结果；更新 `McpContinuationTools` 说明和 `freyja-platform-guide`，不恢复原始工具注册表 |

前后端所有业务 ID、artifact ID 均按字符串传递。事件仍写 `AiTaskEventStore`，不添加另一套内存任务状态。

## 10. 实施顺序与验证边界

1. 统一策略协议、工作台、预览与真实能力发现；兼容标准引导及旧任务。
2. 实现彩噪引导，固定配方和随机性；复用当前 MP4 交接，最先形成可选的新模式。
3. 建立 durable latent 产物协议、归档恢复及依赖指纹，再开放引导＋重绘。
4. 在上述基础上接入上下文噪声和干净帧锚定，开放抗劣化模式。

实施期间进行必要的非 GPU 验证：旧 JSON 兼容、策略解析、模式能力缺失提示、严格协议、四类图接线、局部噪声范围/渐弱与确定性、帧映射、指纹失效、归档失败恢复及历史复用。使用小 tensor 和模拟任务验证遮罩与调度一致性；前端构建检查类型和交互。

遵循用户当前约束，不自动进行真实视频渲染。没有真实视频对照前，仅声明功能已接入且离线校验通过；画质改善、动作保持及额外耗时仍待创作者实际试用。未来如需对比，应固定动作源、提示词、seed、模型/LoRA/VAE和调度配置，比较多次接续后的细节、颜色、身份、动作及边界，而不是只看锐度分数。

## 11. 研究来源与证据边界

- [AIMixer Director 重绘实现](https://github.com/AIMixer/ComfyUI_MiniMaxH3_Director/blob/main/director/h3_latent_continue.py)：latent 前缀、重绘遮罩与逐步 remask。
- [彩噪接续工作流](https://github.com/MacroSony/minimax-h3-chained-character-swap#the-recipe)：22 帧上下文与末 3 帧渐弱配方；[限制与单案例结果](https://github.com/MacroSony/minimax-h3-chained-character-swap/blob/main/references/LIMITATIONS.md)明确其不是普适基准，也未证明存在独立“修复模式”。
- [Geiru Context Noise 源码](https://github.com/roycho87/geiru_context_suite/blob/main/clown_context_noise.py)：随采样加噪、接缝渐弱及上下文恢复；[组合方案](https://github.com/roycho87/geiru_context_suite#degradation-protection-one-switch)还包括干净帧锚定，效果不能只归因于加噪。
- [Context Loop Drift-Control](https://github.com/ethanfel/ComfyUI-MiniMaxH3-Context-Loop/blob/main/drift_control.py)：调度匹配以及 sampler mask 与 H3 timestep 同步。

上述社区实现用于设计参考。具体集成前应固定提交版本、核对部署实例接口及许可证，不能把不同版本的节点随意混用。
