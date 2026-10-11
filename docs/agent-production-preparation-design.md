# FREYJA 分镜渲染准备 Agent 设计方案

日期：2026-10-11。第二阶段已按本方案接入制作 Profile、三张业务表、文字资产与规划工具、交付卡片和补图刷新。实际接口与部署步骤见 [Agent API](agent-api.md)，验收结果见 [第二阶段验收](agent-phase2-validation.md)。本文保留业务设计依据。

实施顺序按最新确认先建设通用基础层与独立 Agent 配置页，详见 [Agent 基础架构设计](agent-foundation-design.md)。本文描述后续制作业务 Profile 的接入；通用配置、会话、执行协议、状态和恢复规则以基础架构方案为准。

## 1. 目标与交付结果

用户通过 Agent 聊天窗口提交小说或剧本，并提出“把这段小说做成视频”的目标。Agent 调用现有拆解流水线，直接保存拆解结果，自主完成分镜模式选择、文字资产建库、素材关联和参考图片方案，交付可在现有工作台继续制作的结果。

本次任务在“渲染准备方案完成”时结束。图片由用户后续生成或上传；Agent 创建资产时填写文字描述、设定和提示词。自动生图、视频渲染、视频质检、配音和成片合成不在本次执行范围内。

交付结果必须包含：

- 已入库的短剧/剧集/情景/镜头组/分镜。
- 每镜明确的生成模式、选择依据、人物与造型、场景或关键图基准、道具和参考素材用途。
- 已有图片的有效绑定；缺图时保存对应资产、用途和待补需求。
- 新建或复用的资产清单，以及需要用户生成图片的清单和受影响分镜。
- 已保存的制作规划；后续聊天可针对具体镜头继续修改。

**任务完成与素材就绪分别记录。** 缺少图片可以是本次任务的正常交付结果；结构不完整、必要决策未完成或保存失败则不能按完整成功交付。

## 2. 现有能力与集成边界

| 能力 | 复用入口 | 新增工作 |
| --- | --- | --- |
| Agent 配置 | 通用 AgentConfigService、/system/agent 配置页 | 注册 production-preparation 模板，复用模型、指令、工具/Skill、记忆、预算和形象配置 |
| 模型调用 | AiModelFactory、Spring AI ChatClient | 为 Agent 配置 CHAT 模型，验证 Tool Calling 能力 |
| 拆解与入库 | ScriptDecomposeService.startDecomposeTask / commit | 包装异步工具，保存草稿与入库检查点，自动调用 commit |
| 人物身份 | CharacterRegistryService、CharacterResolutionService | 复用身份注册与消歧，避免同一人物重复创建 |
| 文字资产 | ResCharacter/Look/Scene/Prop/KeyframeService | 通过 Service 创建、复用和关联资产；关键图创建允许图片 URL 为空 |
| 视觉资产解析 | CharacterVisualAssetResolver、已有参考素材候选机制 | 统一解析人物身份、造型和实际可用图片 |
| 分镜修改 | DramaShotService | 增加 Agent 专用的受控局部应用入口及并发校验 |
| 摄影与提示词 | DirectorPlanningService、ShotAiVisualPlanService、PromptAssembleService | 接收已确定的制作方案，并按素材状态生成或刷新对应提示词 |
| Skills | SkillCatalogService、SkillPromptContextService、load_skill、read_skill_file | 固定一次任务的技能版本，复用现有知识加载 |
| 任务通知 | AiTask、任务中心 WebSocket、事件回放机制 | 任务中心增加 AGENT_RUN 来源，显示聚合进度和结果 |
| 全局 UI | front/src/layout/Index.vue、Pinia、Element Plus | 增加悬浮形象、气泡、聊天抽屉与结果卡片 |

现有 DirectorPlan 负责景别、机位、运镜及 Camera Beats；新增 ShotProductionPlan 负责生成模式、资产与参考素材方案。保留 Planner/Worker 的现有职责，制作规划安排在拆解后执行。

ResKeyframeService.create/update 已按确认需求改造：创建接口图片 URL 非必填，允许先创建关键图文字资产并获得真实 ID，再直接关联分镜。更新省略 URL 保留旧值，显式 clearFrameUrl 清空。AgentMaterialRequirement 统一记录已有资产的补图需求；关键图与其他资产使用同一生命周期。

## 3. 技术选型

| 组件 | 用途 |
| --- | --- |
| Spring Boot 4.1.1、JDK 26 | API、后台执行、状态与事务 |
| Spring AI 2.0.1 | 模型决策、结构化输出、Tool Calling、摘要 |
| MyBatis-Plus Boot 4 Starter、MySQL | 会话、消息、规划、执行检查点和素材需求的持久化 |
| Redis | 上下文缓存、实时事件传递和短期回放 |
| 现有 WebSocket 通道 | 聊天内容、阶段进度、终态结果及虚拟形象通知 |
| Vue 3、TypeScript、Pinia、Element Plus | Agent UI、状态管理和工作台联动 |
| 现有 MinIO / MediaAssetService | 用户提供的虚拟形象图片和已有素材的存储 |

第一版采用单个 Agent Runtime 加现有专业服务。无需新增向量数据库、Embedding 服务、MCP 平台或工作流引擎。大段原文按章节和段落组织，通过来源关联定向读取。所有依赖保持现有 Spring Boot 4、Lombok 和 Jackson 配置兼容。

## 4. 总体架构与执行流程

~~~mermaid
flowchart TD
    U[用户输入小说和制作要求] --> R[Agent Runtime]
    R --> D[现有拆解流水线]
    D --> C[自动入库并绑定项目]
    C --> A[全剧资产盘点与文字资产建库]
    A --> P[按镜头组生成制作规划]
    P --> V[结构校验与受控应用]
    V --> M[绑定已有图片并保存缺图需求]
    M --> S[提示词处理与就绪检查]
    S --> O[保存交付报告并完成任务]
    O --> N[虚拟形象气泡与聊天结果]
    R <--> K[会话摘要和项目记忆]
~~~

执行按下列阶段推进：

1. **接收任务**：保存原文、用户要求、目标项目或新建项目意图，以及模型、Skill、预算和作用域快照。
2. **拆解**：调用现有异步入口，关联子任务 ID。部分 Worker 失败时只重试受影响部分，完整拆解通过现有校验后再入库。
3. **入库**：通过现有 commit 级联保存。已由拆解创建的资产立即进入资产候选清单；后续资产工具复用它们。
4. **资产整理**：结合注册表、原文证据和已有设定，生成共享资产清单。先保存角色，再保存相应造型，以及场景和道具。
5. **制作规划**：按镜头组加载当前镜头、前后镜头、角色状态、资产候选和生成模式能力。AI 返回结构化 ShotProductionPlan。
6. **应用配置**：校验后保存模式、关联和参考素材需求。每次应用是一个独立、可恢复的步骤。
7. **检查与交付**：检查规划覆盖率、图片完整性和配置有效性，保存报告，发送完成事件。本次 Run 至此结束。

整章原文不重复注入每个镜头。上下文由项目摘要、镜头来源片段、当前镜头组和必要候选资产组成。

## 5. 制作规划协议

ShotProductionPlan 保存以下信息：

| 字段 | 含义 |
| --- | --- |
| shotId / runId / revision | 对应分镜、来源任务和规划版本 |
| inputFingerprint | 原文、分镜、相关资产、模型与 Skill 配置的输入指纹 |
| generationMode / decisionReason | FIRST_LAST_FRAME 或 REFERENCE_MODE，以及 AI 的选择依据 |
| characterBindings | 角色、明确造型、动作/状态和是否可见 |
| visualAnchor | 选择场景或关键图作为主视觉基准；缺图关键图也引用已创建的真实资产 ID |
| propBindings | 道具及在当前镜头中的用途 |
| referenceSlots | 有序的参考素材槽位，包括来源、用途、必需性和可用状态 |
| firstFrameRequirement / lastFrameRequirement | 首尾画面要求，是否必需由方案和模型能力明确决定 |
| lockedFields | 用户明确锁定的字段 |
| preparationStatus / missingRequirements | 素材状态和待补清单 |

AI 决定模式时考虑叙事意图、起止画面控制、人物和造型一致性、空间关系及当前支持的模型能力。Java 校验枚举、归属、图片上限、角色关联、互斥关系和结构完整性；不根据剧情关键词自行推断模式、人物动作或素材用途。

模式范围使用当前系统的 FIRST_LAST_FRAME / REFERENCE_MODE。若指定目标视频模型，则依据模型中心的明确能力配置检查兼容性；图片数量和用途上限由实际协议决定，集中校验，不复用各处可能过时的注释。

### 5.1 业务关联与图片引用

业务关联回答“镜头包含什么”，参考槽位回答“用什么图片约束什么”。一个角色参与画外对白，不代表其必须作为可见主体出镜。

- 人物用途采用现有 SUBJECT / NON_SUBJECT；身份、造型等约束通过 referenceRole 单独表达。
- 场景与道具明确承担布局、外观等用途。
- 关键图沿用现有七种 KeyImageRole。COMPOSITION_ANCHOR 等规划参考不等同于首帧或尾帧。
- resSceneId 与 resKeyframeId 的主视觉绑定继续遵守互斥规则，所属 DramaScene 的叙事关系仍然保留。
- CHARACTER_REFERENCE 的 sourceId 使用素材候选服务返回的真实图片记录 ID；角色 ID 和造型 ID 使用对应专用字段，禁止混用。

### 5.2 待补图与真实参考图

referenceSlots 是完整的计划；DramaShot.refImagesJson 只包含实际存在、可用并通过校验的图片引用。缺图需求不以空 URL 或虚构 Picture 编号写入有效引用。

示例为已创建文字资产、等待图片的镜头规划；所有业务 ID 对前端均为字符串：

~~~json
{
  "shotId": "2032095628944588801",
  "generationMode": "REFERENCE_MODE",
  "decisionReason": "需要保持雨衣造型与怀表外观，参考素材分别约束人物、空间和道具。",
  "referenceSlots": [
    {
      "slotKey": "lead-look",
      "sourceType": "CHARACTER_LOOK",
      "sourceId": "2032095628944588802",
      "characterId": "2032095628944588803",
      "usageRole": "SUBJECT",
      "referenceRole": "COMBINED",
      "required": true,
      "materialRequirementId": "2032095628944588804",
      "status": "MISSING"
    }
  ],
  "preparationStatus": "WAITING_ASSET_IMAGES"
}
~~~

上述 CHARACTER_LOOK 是新增规划协议的逻辑来源类型。图片就绪后，使用已有解析服务转换成渲染协议中的 CHARACTER_REFERENCE 或已有兼容格式；这份计划不直接充当渲染请求。

### 5.3 提示词与素材变更

文字设定和镜头画面要求在本次任务中保存完整。已有素材齐全时，复用导演规划和提示词服务生成并保存当前模式对应的内容。

缺图时保存镜头文字方案和素材用途；最终依赖 Picture/Subject 编号的提示词标为待更新。真实图片绑定后依据完整 Reference Manifest 生成，避免缺图导致编号漂移或虚假引用。

模式、图片顺序、用途或关联资产变化时，制作方案和提示词指纹同步失效。DirectorPlan 的修改沿用现有版本化入口与显式锁定规则，不绕过服务直接写 directorPlanJson。

## 6. 文字资产与缺图清单

| 资产 | 创建时填写 | 图片为空时 |
| --- | --- | --- |
| 角色 | name、canonicalName/已知称谓、personality、appearanceDesc、appearancePrompt、原文证据 | 保存角色并记录身份图需求 |
| 造型 | characterId、lookName、lookType、designDesc、appearancePrompt、outfitPrompt | 保存造型，使用现有 MISSING 状态，记录造型图需求 |
| 场景 | name、description、已知时间/环境、scenePrompt | 保存场景并记录场景图需求 |
| 道具 | name、propType、description、propPrompt | 保存道具并记录道具图需求 |
| 关键图 | 名称、用途、构图、动作、prompt、画幅、关联分镜 | frameUrl 可空，先保存 ResKeyframe 并关联分镜，记录补图需求 |

原文已确定的事实保持一致；未知身份、时间、天气等字段保留 null，或继承用户明确指定的设定。AI 可以组织画面描述和制作表达，不能把未知剧情事实写成已确认事实。人物身份与服饰造型分别保存，复用现有消歧机制。

素材需求统一去重：同一人物造型被十个镜头引用，交付一条造型补图需求，并列出十个受影响镜头。关联键使用资产/造型身份及用途，不仅使用显示名称。新建文字资产在报告中全部列出；是否阻碍渲染由必需性决定。

清单字段包括资产类型、真实资产 ID、素材需求 ID、名称、图片制作描述、用途、缺失状态、受影响镜头和资源编辑入口。已有资产缺图或明确标为 STALE 时也进入待处理清单。

### 6.1 关键图接口改造

- 创建时 frameUrl 省略、null 或空白均按未提供图片处理，保存为 null；名称、文字描述/提示词、用途和归属继续校验。
- 缺图关键图立即拥有真实资产 ID，允许作为分镜的主视觉基准或规划参考来源。实际图片槽位等待 frameUrl 补齐后解析。
- 更新时省略 frameUrl 保留原值；已有缺图资产可以只修改文字。显式清空图片时记录待补状态，并使受影响镜头的准备状态失效。
- 补图时更新同一条关键图资产及其来源信息，不创建第二条资产。无图片的文字草稿应使用明确的草稿来源，例如新增 TEXT_DRAFT；不能标为已生成图片。
- 前端关键图表单同步允许先保存文字，缺图卡片展示“待补图”。素材候选服务区分文字资产与可用图片，渲染参考池只返回有真实图片的记录。
- 图片状态可在查询时按 URL 和素材指纹推导；若沿用统一 MISSING/SYNCED/STALE 字段，则在资源编辑与准备检查中集中维护。

## 7. 运行状态与素材状态

### 7.1 Agent Run

| 状态 | 含义 |
| --- | --- |
| QUEUED | 已持久化，等待执行 |
| RUNNING | 正在拆解、建库、规划或保存 |
| WAITING_TASK | 等待已有异步 AI 子任务 |
| WAITING_USER | 有影响执行的真实歧义或配置问题，需要用户回答 |
| COMPLETED | 全部分镜规划和配置保存成功，交付报告已保存 |
| PARTIAL_SUCCESS | 部分镜头或阶段成功，剩余部分可局部重试 |
| FAILED | 任务未完成，错误信息及已完成步骤仍保留 |
| CANCELLED | 已停止后续执行，已保存数据列入报告 |

缺图不进入 WAITING_USER；完整规划结束后可为 COMPLETED，报告说明需要用户补图。未知角色仍可使用注册表中明确的临时身份记录；不能解决且影响绑定的冲突进入 WAITING_USER 或局部未完成状态。

### 7.2 单镜准备状态

| 状态 | 含义 |
| --- | --- |
| PENDING | 尚未完成制作规划 |
| WAITING_ASSET_IMAGES | 配置方案已保存，必需图片尚未齐全 |
| NEEDS_REFRESH | 输入、素材或配置已变化，方案/提示词需要更新 |
| READY | 模式与关联合法，必需素材齐全，当前模式所需提示词有效 |
| ERROR | 规划或应用失败，需要修复 |

用户补图后，通过“刷新准备状态”入口重新解析素材并应用有效绑定。若需更新提示词，可创建局部刷新任务；原准备 Run 保持已完成，保留最初的交付快照。第一版由用户显式刷新，后续可以监听资源更新事件自动触发同样的局部逻辑。

READY 描述配置和素材完整性，不代表生成视频的最终视觉质量。首版依据图片所属角色/造型、资产描述和用途选择参考图，默认不做像素级视觉判断。

## 8. Runtime 与工具执行

建议在 com.astra.freyja.agent 下组织 runtime、model、service、tool；实体、DAO 和 Controller 保持项目现有约定。

| 新组件 | 职责 |
| --- | --- |
| AgentRuntime | 显式管理模型调用与工具反馈循环，推进阶段和检查点 |
| AgentToolRegistry / AgentToolExecutor | 按阶段注册工具，参数校验、幂等执行和结构化返回 |
| AgentProductionPlanningService | 生成及校验制作规划，按镜头组管理上下文 |
| AgentPlanApplyService | 局部修改分镜并维护规划版本、指纹和变更记录 |
| AgentMaterialService | 素材需求去重、候选解析、缺图清单和就绪检查 |
| AgentMemoryService / AgentContextBuilder / AgentMemorySummarizer | 会话持久化、上下文组装和摘要 |
| AgentRunService / AgentRecoveryScheduler | 状态、检查点、任务等待、取消和重启恢复 |
| AgentEventPublisher | 持久化事件后推送到任务中心和聊天窗口 |

第一批工具：

| 工具 | 操作 |
| --- | --- |
| get_project_context / list_shots | 查询当前项目和镜头 |
| start_script_decomposition / get_task_result | 提交现有拆解任务并读取结果 |
| commit_decomposition | 经现有校验后将拆解结果入库 |
| search_assets / ensure_text_asset | 查询候选，复用或创建文字资产 |
| get_reference_candidates | 返回真实资产图片及其来源字段 |
| save_shot_production_plan / apply_shot_plan | 保存并应用校验通过的制作规划 |
| record_material_requirement | 为已创建或复用的资产保存待补图片需求 |
| prepare_shot_prompts | 调用已有导演/提示词服务处理素材已就绪镜头 |
| inspect_preparation / get_run_report | 重算准备状态和读取交付结果 |
| load_skill / read_skill_file | 获取当前任务版本的专业知识 |

工具返回固定 Schema，包括成功状态、错误码、业务 ID、版本和子任务 ID。Tool Calling 的暂停、预算和业务副作用由 Runtime 显式控制，避免把完整长任务交给不可恢复的一次调用。

工具作用域来自服务端保存的会话/Run，参数只能指向本任务允许的项目和对象。新增资产默认归当前短剧；公共库作为只读复用来源。用户提出制作目标并确定范围后，本流程内的正常创建和修改连续执行；只有实质歧义或扩大范围才需要用户输入。

## 9. 持久化、幂等与恢复

以下是建议的逻辑表，实施时为高频查询和唯一约束建立索引：

| 表 | 关键内容 |
| --- | --- |
| agent_config | 基础层统一保存每个 Agent 的模型、指令、工具/Skill、记忆、预算和形象配置及版本；制作 Profile 复用该表 |
| agent_session | 项目作用域、标题、会话摘要、摘要覆盖位置、最后活动时间 |
| agent_message | sessionId、runId、顺序号、role、消息类型、正文/结构化负载、toolCallId、已读状态 |
| agent_run | 用户目标、原文及章节来源、配置快照、状态、阶段、预算、交付报告、租约和检查点 |
| agent_step | runId、逻辑步骤键、尝试次数、输入输出、关联 AiTask、幂等键、字段差异和执行状态 |
| agent_memory | PROJECT/SESSION 作用域、记忆键、用户明确规则、来源消息、版本和启用状态 |
| shot_production_plan | 分镜最新规划、版本、输入指纹、参考槽位、锁定字段和准备状态 |
| agent_material_requirement | 项目内去重素材键、来源资产、用途、文字描述、图片状态、补图结果 |

agent_message 同时承载可回放的结构化阶段消息和完成通知。MySQL 顺序号是可靠事件游标，Redis 与 WebSocket 负责实时传递；无需为首版另外建立一套 Agent 事件表。

- agent_run 是 Agent 聚合任务的状态来源；已有 AiTask 管理其专业子任务。关联记录放在 agent_step，并在任务中心映射为新的 AGENT_RUN 来源。
- 拆解 commit 需要任务级幂等适配：在同一事务中完成业务入库与 commit 步骤结果保存。若提交成功后连接中断，重试读取已保存目标 ID，不再次创建整部剧。
- 资产创建使用 Run/逻辑资产键；步骤重试不会重复建库。跨 Run 的语义复用通过资产候选与角色注册表决策，并在短事务中重新核对。
- 修改分镜使用预期规划版本和目标字段指纹，在同一事务中校验并应用。冲突时保留用户新编辑内容，重新读取后局部规划。
- MySQL 保存执行租约和检查点。后台扫描认领到期任务；等待子任务时通过终态事件唤醒，定时扫描补偿漏发事件。
- 取消、租约代次和写入资格在事务中校验，防止失去执行资格的旧线程继续修改。保存已提交内容并报告影响范围。
- 步骤、模型 Token、超时与重试预算统一配置；恢复按最后持久化状态继续。Token 未被提供商返回时记录未知，不伪造用量。
- 历史变更可以生成撤回操作；撤回必须核对当前版本，避免覆盖后续手工编辑或删除仍被引用的资产。

## 10. 记忆设计

记忆使用 MySQL 持久化，Redis 缓存，Spring AI 负责模型上下文及摘要生成。

1. **对话记忆**：保存用户、助手、工具调用和工具结果；恢复时保留完整调用/结果配对。工具调用待完成时，先恢复该调用，再继续模型交互。
2. **摘要记忆**：近期完整对话加已覆盖历史的摘要，记录 coveredThroughSeq。摘要保存当前目标、已确认决策、完成事项和待处理问题，原始消息继续保留。
3. **项目记忆**：用户明确提出的风格、模式偏好、锁定要求和修改指令，保存来源与作用域；撤回或替换时更新版本。
4. **执行记忆**：工具结果、业务 ID 和恢复检查点保存在 Run/Step，不依赖自然语言摘要推断执行进度。

每次调用模型前，AgentContextBuilder 读取当前业务事实、有效项目规则、摘要、近期消息、来源段落和有限候选资产。资产和分镜当前事实以业务数据库为准；旧摘要中的值不覆盖现状。

摘要按上下文预算触发，摘要更新与覆盖游标原子保存。长原文按章节/段落关联读取，不直接充当整段对话历史。后续若出现跨大量小说或项目的语义检索需求，再评估向量检索。

## 11. 虚拟形象与聊天 UI

新增 AgentAvatarWidget，挂载于全局布局，在各业务页持续存在；点击切换 AgentChatDrawer 聊天窗口。悬浮形象避开现有任务入口和弹窗，支持拖动、收起，并保存用户位置。

用户在“管理 → Agent 配置”（/system/agent）上传 Codex Pet v1/v2 ZIP，或点击恢复默认。包包含 pet.json 与透明 PNG/WebP 精灵图，使用基础层 agent_config 的 avatar.pet 保存。前九行驱动标准状态动画，图片只负责展示，执行状态以服务端 Run 为准。

上传校验后复用 MediaAssetService / MinIO 保存精灵图原始字节，前端通过精灵行与逐帧时间播放，放弃 GIF。配置页提供九行动作预览和默认包下载，无测试面板。入口为更大的透明无边框宠物，点击可打开或关闭非模态聊天窗口，拖动时播放移动动作。完成气泡由已保存的终态事件驱动。具体协议沿用基础架构方案 11.1。

| 展示状态 | 对应语义 | 气泡示例 |
| --- | --- | --- |
| IDLE | 无活动任务 | 点击与我聊天 |
| THINKING | 正在进行模型规划 | 正在选择分镜制作方式… |
| WORKING | 拆解、建库、关联或保存 | 正在整理人物与场景… |
| SUCCESS | 任务已完成且必需素材齐全 | 分镜准备完成，点击查看 |
| ATTENTION | 任务已完成但有待补图清单，或确需用户回答 | 已新建 5 个资产，需要你生成图片 |
| ERROR | 失败或部分失败 | 有 3 个分镜未完成，点击处理 |

交互约定：

- 点击形象打开当前/最近会话；点击气泡携带 sessionId/runId，定位对应交付报告。
- 不自动展开聊天窗口。关闭窗口不取消后台任务；重新打开恢复消息和状态。
- 任务完成后提醒一次，气泡收起后保留未读标记；用户打开对应结果后标为已读。
- 同一 Run 的重复/回放完成事件按事件 ID 去重。聊天已打开并展示该结果时直接插入消息。
- 人物整理等阶段进度显示在聊天内；默认只对任务完成、失败和必要用户操作显示气泡。
- 多任务通知分别保存，形象显示当前任务或待处理状态；旧任务通知可以直接打开原会话。

AgentChatDrawer 包含输入框、原文提交入口、当前项目、任务进度、步骤摘要和 AgentResultCard。结果卡片可打开分镜、资产编辑页及缺图清单，允许用户继续用自然语言局部修改。

建议前端文件：components/agent/AgentAvatarWidget.vue、AgentChatDrawer.vue、AgentResultCard.vue、AgentMaterialChecklist.vue，以及 store/agent.ts、api/agent.ts、types/agent.ts。

## 12. API 与事件约定

| 方法与路径 | 用途 |
| --- | --- |
| POST /agent/sessions | 创建或选择项目会话 |
| GET /agent/sessions | 查询会话列表 |
| GET /agent/sessions/{id}/messages?afterSeq= | 加载历史或断线后增量消息 |
| POST /agent/sessions/{id}/messages | 发送目标或后续指令，按需创建 Run，立即返回 ID |
| GET /agent/runs/{id} | 查询阶段、状态和作用域 |
| GET /agent/production/runs/{id}/report | 查询已保存的制作交付报告 |
| POST /agent/runs/{id}/cancel | 取消后续执行 |
| POST /agent/runs/{id}/resume | 恢复失败任务；指定镜头的后续修改通过新聊天目标提交 |
| POST /agent/production/start | 原文、项目、风格与要求幂等提交 |
| GET /agent/production/shots/{id}/plan | 查询最新制作规划、锁和准备状态 |
| PUT /agent/production/shots/{id}/locks | 按版本锁定/解锁规划字段 |
| POST /agent/production/refresh | 针对补图后的指定镜头局部刷新，不覆盖手工编辑 |
| POST /agent/runs/{id}/read | 标记完成通知已读 |
| GET /agent/profiles | 获取可用 Agent 及名称、形象等展示配置，沿用基础层 |

配置读取、保存、校验和测试统一使用基础层 /system/agent/configs/** 接口。Codex Pet 虚拟形象通过独立配置页维护，不另设一份形象配置或业务专用配置表。

已有 WebSocket 通道扩展 Agent 订阅。事件携带 sessionId、runId、seq、type 和 payload，实际沿用基础层 MODEL_STARTED、TOOL_STARTED、TOOL_COMPLETED、MESSAGE_SAVED、WAITING_TASK、RUN_COMPLETED、RUN_FAILED 等事件，完整回答在保存后推送。断线重连先按持久化游标补齐，再继续实时接收；完成报告和可通知事件在事务提交后发布。素材图片继续通过既有资产编辑接口上传/更新，无需独立 fulfill 接口。

前端与 JSON 中所有 Snowflake ID 使用字符串。序列号用于排序，与业务 ID 分开处理。局部应用请求区分“省略字段”和“显式清空”，沿用现有分镜 DTO 语义。

## 13. 交付报告

报告包括范围、分镜数量、模式分布、资产新建/复用数量、可渲染数量、待补图数量、失败数量、各镜头配置及决策依据，并保存为本次 Run 的不可变交付快照。当前最新准备状态另外查询，避免用户补图后篡改历史结果。

示例：

> 已完成 36 个分镜的拆解和配置。首尾帧模式 14 镜，参考图模式 22 镜。复用 8 个资产，新建 7 个文字资产，其中包含 2 个关键图资产。12 镜素材就绪，24 镜等待补图。点击清单可查看描述、用途和受影响分镜。

全部数字从已保存的业务记录和准备状态计算，Agent 自然语言回复引用这些结果，不自行编造统计。

## 14. 实施顺序与验收

以下步骤在通用基础层与独立 Agent 配置页完成后推进，各步串成同一条完整链路：

1. 注册制作 Profile 和配置选项，沿用基础层会话、消息、Run/Step、任务中心及恢复机制，打通原文提交、现有拆解与幂等入库。
2. 改造关键图创建/更新与前端表单以支持无图文字资产，实现全剧文字资产整理、ShotProductionPlan、局部应用、素材需求去重和参考槽位解析。
3. 实现就绪检查、提示词处理、交付报告及用户补图后的局部刷新。
4. 接入制作专属上下文与结果卡片，扩展缺图清单和准备状态气泡，复用基础层记忆、虚拟形象、聊天抽屉及历史恢复，完成端到端验收。

关键验收场景：

- 提交一章文本后，分镜、资产、模式和关联自动保存，并能在现有工作台查看。
- 已有正确素材的镜头绑定真实图片；缺图镜头保存需求，完整准备任务仍可正常交付。
- 多镜头引用同一人物、造型、场景或道具时复用资产和补图需求。
- 新建角色/造型/场景/道具只有文字描述，报告准确提示需要用户生成图片。
- 关键图可在 URL 为空时创建、保存文字并关联分镜；用户补图更新原资产，缺图期间不会作为有效图片传入渲染参考池。
- 画外人物、身份与造型、关键图用途、场景/关键图互斥均通过已有约束与新增结构校验。
- 模式改变后旧提示词失效，缺图规划不会生成虚假图片 URL 或 Picture 编号。
- 刷新页面、关闭抽屉、断线与服务重启后，能够恢复历史和合法检查点；不会重复入库或建资产。
- 并发手工编辑受到版本保护；局部失败可以局部重试；取消后停止新增修改。
- 完成通知只在结果保存后出现一次，点击进入正确会话与报告，未读状态可恢复。
- 制作 Agent 在独立配置页可设置模型、工具、预算和Codex Pet ZIP；精灵图状态动画正常播放，执行配置修改只影响新 Run，已有 Run 保留快照。
- 后续对话能读取明确项目规则和最新业务事实，不串项目记忆。
- Agent 工具集合不包含图片生成或视频渲染，本次交付到分镜渲染准备为止。

实现沿用 JDK 26、Maven Wrapper 和指定 Node 验证，未新增运行时依赖。当前候选查询上限为 200 个角色及其造型、200 场景、200 道具和 200 关键图；大规模项目的候选分页、跨实例广播及逐 Token 文字流属于后续增强。
