# FREYJA Agent 基础架构设计

日期：2026-10-11。本文定义第一阶段基础架构，核心已按本方案实现，实际接口与部署步骤见 [Agent 使用与 API](agent-api.md)。剧本制作业务接入参见 [分镜渲染准备 Agent 设计](agent-production-preparation-design.md)。

实现对齐：首版使用 MySQL 检查点和进程内事件发布，不增加 Redis 事件层；`AgentStore` 统一管理会话、Run、Step 与消息，Profile 内提供完成检查。推送完整回答与步骤进度，逐 Token 预览保留为后续增强。外部等待采用周期扫描，基础工具尚不提交制作任务；集群广播和跨实例并发限制不在本次实现范围内。

## 1. 第一阶段目标

建立可在 FREYJA 内运行的 Agent：接收用户目标，读取上下文，自主选择已注册工具，执行并观察结果，保存会话和运行状态，向界面推送进度，支持继续聊天、取消和恢复。

基础层负责通用机制；拆解、模式选择、资产创建和参考素材规划由后续业务 Profile 与工具提供。先验证基础执行闭环，再接入完整分镜渲染准备流程。

首版交付：

- 一个可配置模型和工具的 core-assistant Profile。
- 独立的“Agent 配置”管理页，配置模型、指令、工具、Skills、记忆、执行参数和虚拟形象，并支持测试。
- 持久化会话、消息、Run、Step 与明确记忆。
- 自己控制的模型/工具循环，支持结构化工具调用和结果反馈。
- HTTP 提交与查询、共享 WebSocket 进度和断线回放。
- 上下文组装、近期历史、会话摘要和项目规则读取。
- 幂等提交、执行检查点、任务等待、取消和重启恢复。
- 基础聊天窗口、状态图片入口和完成气泡。

首版范围不包含整部小说制作流水线、图片生成、视频渲染、多 Agent 调度、可视化流程编辑器、向量检索或外部工具市场。已确认的“关键图创建 URL 非必填”属于后续资产工具接入工作。

## 2. 技术路线与既有组件

采用现有 Spring Boot 4.1.1、JDK 26、Spring AI 2.0.1、MyBatis-Plus、MySQL、Redis、WebSocket，以及 Vue 3 / TypeScript / Pinia / Element Plus。

| 既有组件 | 基础层使用方式 |
| --- | --- |
| AiModelFactory | 按提供商和模型创建 ChatModel，保留现有熔断和配置 |
| Spring AI | 传递消息、工具定义、模型选项，取得回答、工具调用和用量 |
| SysConfigService | 保存系统级执行硬上限与公共默认值；各 Agent 的可编辑配置统一保存到 agent_config |
| SkillCatalogService / SkillPromptContextService | 创建 Skill 目录版本快照并按需加载 |
| MyBatis-Plus / MySQL | 保存可靠状态、消息、检查点、幂等结果和执行租约 |
| Redis | 继续由现有业务使用；首版 Agent 不额外缓存状态或传递事件 |
| TaskCenterService / TaskCenterWebSocketHandler | 扩展 AGENT_RUN 摘要和独立 Agent 事件订阅 |
| MediaAssetService / MinIO | 保存用户提供的状态图片，复用素材上传入口 |

Spring AI 是模型与工具协议适配层，AgentRuntime 是应用自己的执行调度层。具体自动工具执行开关按项目实际 Spring AI 2.0.1 API 在实现时验证，不直接沿用示例中的过时 API。

Agent 使用独立 AgentExecutionScheduler 和执行器，与渲染线程池分开控制负载。JDK 虚拟线程可以承担阻塞 I/O，但并发认领和模型调用仍需限流。第一版部署一个后端实例，也采用数据库租约和写入校验，为后续多实例保留正确边界。

## 3. 分层架构

~~~mermaid
flowchart TD
    UI[聊天窗口 / 虚拟形象] --> HTTP[Agent Controller]
    SETTINGS[Agent 配置页] --> CONFIG[AgentConfigService]
    CONFIG --> DB[(MySQL)]
    HTTP --> RUN[AgentRunService]
    RUN --> CONFIG
    RUN --> DB
    RUN --> SCHED[执行调度与恢复扫描]
    SCHED --> RT[AgentRuntime]
    RT --> CTX[AgentContextBuilder]
    CTX --> MEM[AgentMemoryService]
    RT --> MODEL[AgentModelGateway]
    MODEL --> FACTORY[现有 AiModelFactory]
    RT --> EXEC[AgentToolExecutor]
    EXEC --> TOOLS[AgentToolRegistry]
    TOOLS --> ADAPTER[业务 Service / Skill 适配工具]
    RT --> FINISH[AgentCompletionPolicy]
    RT --> EVENTS[持久化进度与事件发布]
    EVENTS --> WS[现有任务中心 WebSocket]
    WS --> UI
~~~

关键边界：

- Runtime 不依赖 DramaShotService 等具体制作服务；依赖工具协议和插件接口。
- 工具执行器控制工具访问、参数、幂等和恢复；工具实现调用现有 Service。
- Profile 提供工具集合、提示词、上下文贡献者和完成策略。
- 会话和 Run 状态由 MySQL 管理；前端与缓存读取该状态。
- 面向用户显示回答、操作摘要和简要决策依据；执行协议不依赖模型隐藏推理内容。

## 4. 核心对象

| 对象 | 定义 |
| --- | --- |
| AgentProfile | 代码注册的能力模板：职责边界、可选/必需工具、上下文贡献者和完成策略 |
| AgentConfig | 配置页保存的名称、模型、指令、工具选择、记忆、预算和形象配置，带版本 |
| AgentSession | 持续会话，绑定 Profile、可选项目作用域和摘要 |
| AgentRun | 一次用户目标及其执行过程、模型快照、预算、状态和结果 |
| AgentMessage | 有序会话条目：用户、助手、工具结果或结构化事件 |
| AgentStep | 一次模型调用、工具执行或完成检查，带稳定执行键和结果 |
| AgentMemory | 用户明确提出的会话/项目规则及来源 |
| AgentTool | 固定 Schema 的能力实现及恢复策略 |
| AgentToolContext | 服务端确定的作用域、Run、Step、取消令牌和幂等键 |

同一 Session 同时只允许一个活动 Run，减少会话历史和写入冲突。不同 Session 可以在全局并发限制内执行。用户输入意图使用明确请求字段，Java 不通过关键词猜测是新任务还是中途调整。

### 4.1 AgentProfile

首版采用“代码注册能力模板 + 数据库保存可编辑配置 + 独立配置页面”。Profile 决定可用能力的边界，AgentConfig 决定这个 Agent 的实际配置。首版提供 core-assistant，后续注册 production-preparation 后在同一页面出现制作 Agent 的配置。

Profile 内容包括：

- 稳定的 agentCode、模板版本、默认名称、职责和不可移除的执行约束。
- CHAT / TASK 类型、必需工具、可选工具的上限、允许的项目操作范围。
- 默认指令、模型能力要求、默认预算及可编辑字段范围。
- 必用 Skill、可选 Skill 范围和版本快照策略。
- ContextContributor 和 CompletionPolicy。

AgentConfigService 负责初始化配置、合并模板默认值、校验保存和解析有效配置。首次注册模板时幂等创建一条停用配置；用户选择模型并保存启用后可创建任务。更新应用或模板不覆盖用户已保存的设置，新增必需能力若不兼容则显示配置问题，要求重新保存。

有效执行配置由“Profile 固定约束 + AgentConfig + 允许的本次模型覆盖”组成，并受到系统硬上限限制。各 Agent 的模型、提示词、记忆、预算和形象只在 agent_config 保存，SysConfig 不再保存这些字段的另一份副本。公共默认值在初始化时填入，保存后以 AgentConfig 为准。

Run 创建时在同一事务内读取已保存配置，固定 agentCode、configRevision、模板版本、指令全文、模型选项、记忆策略、预算、工具 Schema 版本及 Skill 版本到 profileSnapshotJson。后续聊天创建的新 Run 使用最新配置；活动 Run 和同一 Run 的恢复使用原快照。形象属于展示配置，保存后可立即刷新界面，不影响执行快照。

停用 Agent 只阻止新建普通 Run，已有 Run 继续执行，取消使用显式取消入口。模型/提供商停用或工具实现不可用时报告明确错误，不默默切换模型或扩大工具集合。配置中不复制凭据；提供商密钥仍由现有提供商服务管理。恢复时检查工具版本是否可用，必要时报告兼容性问题。

## 5. Runtime 执行循环

~~~text
创建用户消息与 Run
  -> 调度器认领 Run
  -> 读取取消状态、作用域和检查点
  -> 组装模型上下文
  -> 调用模型，保存完整回答和工具调用
  -> 有工具调用：校验并逐个执行，保存工具结果
       -> 即时结果：反馈模型，继续循环
       -> 异步工具：保存 waitHandle，进入 WAITING_TASK
  -> 无工具调用：调用完成策略
       -> 校验通过：保存最终结果，进入 COMPLETED
       -> 需要修正：把结构化问题反馈给模型继续
       -> 需要回答：进入 WAITING_USER
  -> 持久化终态事件并通知前端
~~~

AgentModelGateway 必须能返回未执行的 ToolCall，包括 callId、工具名称和参数。实际业务工具由 AgentToolExecutor 执行，禁止让模型客户端内部自动循环绕过检查点、取消校验和幂等记录。

一次模型返回多个工具调用时，首版顺序执行。完整模型回答及全部调用先落库，再进行任何业务写入；下一模型调用只能读取已完成的结果配对。

模型响应已经落库但尚未执行的工具可以恢复。模型响应未落库时允许重新进行推理，不认为之前临时输出已完成；尚未调用业务工具，因此不会据此重复业务写入。

### 5.1 状态机

| Run 状态 | 含义 |
| --- | --- |
| QUEUED | 已持久化，等待认领 |
| RUNNING | 模型调用、工具执行或完成检查 |
| WAITING_TASK | 等待可查询的外部/已有 AI 任务终态 |
| WAITING_USER | 工具或完成策略提出必须回答的问题 |
| CANCELLING | 收到取消，停止后续操作并处理中断 |
| COMPLETED | 完成策略通过且最终结果已保存 |
| PARTIAL_SUCCESS | Profile 明确允许部分交付，结果中列明未完成部分 |
| FAILED | 失败，保留已保存的步骤和错误 |
| CANCELLED | 取消处理结束，保留已提交操作的说明 |

Step 状态包含 PENDING、RUNNING、WAITING_EXTERNAL、RECONCILING、SUCCEEDED、FAILED、CANCELLED。Run 不能仅凭模型回复“完成”进入成功，必须通过 Profile 对应的完成策略。

## 6. 工具协议与执行规则

AgentTool 的实现使用 Java DTO 描述输入输出，工具定义由统一注册器生成 Schema；概念接口如下，具体 Spring AI 类型由 Gateway 适配：

~~~java
public interface AgentTool<I, O> {
    AgentToolDescriptor descriptor();
    ToolResult<O> execute(I input, AgentToolContext context);
    RecoveryResult<O> reconcile(AgentToolContext context);
}
~~~

AgentToolDescriptor 提供名称、版本、用途、输入 Schema、作用域类型、效果类型 READ / WRITE、恢复模式及参数/结果大小限制。工具版本标识执行契约，不能仅靠名称恢复历史调用。

ToolResult 区分：

- IMMEDIATE：成功数据或结构化错误，例如 NOT_FOUND、INVALID_ARGUMENT、VERSION_CONFLICT。
- DEFERRED：waitHandle，包括适配器类型、任务 ID、截止时间和查询依据。终态到达后才形成最终工具结果并继续模型交互。
- NEED_USER：结构化问题和允许回答，Run 进入 WAITING_USER；用户回答后恢复同一目标。

### 6.1 幂等与外部任务

| 恢复模式 | 使用条件 |
| --- | --- |
| REPLAY_SAFE | 只读或明确可安全重复的操作，可在失联后再次执行 |
| TRANSACTIONAL | 本地写入与工具成功检查点能在同一数据库事务提交 |
| RECONCILE | 外部提交结果可能未知，必须按幂等键查询已有操作后决定继续 |

幂等键绑定持久化的逻辑调用，不仅使用工具名称和参数哈希；模型在后续轮次重新查询同一对象属于新的读取。外部写操作如果没有可用幂等键或查询恢复机制，不纳入首版可自动恢复工具集合。

异步提交在成功后保存 waitHandle。若提交响应丢失，先由 reconcile 查询原任务，不盲目再次提交。等待期间释放执行线程和模型并发占用，终态事件唤醒；周期扫描负责补偿漏发事件。

工具作用域由 Session/Run 服务端绑定。模型参数不得扩大项目范围；前端业务 ID 保持字符串，服务端正常解析 Long 并校验真实归属。后续制作工具通过专用局部更新 DTO 传递明确变更，支持版本冲突。

### 6.2 首版工具与验证工具

生产环境先接入：get_project_overview、list_project_assets、get_shot_detail、save_session_note、list_session_notes，以及已有 load_skill / read_skill_file。保存会话笔记可验证本地写入闭环，不改造剧本资产流程。

异步等待、外部响应丢失和取消竞争通过测试专用 FakeAsyncTool / FakeRecoverableTool 验证，这些工具只在测试配置启用。已有 AiTask 后续通过单独适配器实现等待协议，核心 Runtime 不直接依赖某类拆解任务。

## 7. 记忆与上下文

AgentMemoryService 管理持久化记录，AgentContextBuilder 组装每轮模型输入，AgentMemorySummarizer 压缩历史。

模型输入按以下顺序组织：

1. Profile 系统指令和已固定的 Skill 上下文。
2. 用户明确的项目/会话规则及当前目标。
3. 已压缩历史摘要及覆盖位置。
4. 近期完整消息，保留助手工具调用与对应工具结果。
5. ContextContributor 按明确关联读取的最新业务事实。

默认贡献者提供基本项目摘要；制作业务贡献者后续提供原文片段、镜头组、角色注册表和素材候选。原文存储与切分由业务接入层承担。

摘要触发依据上下文预算，保存 coveredThroughSeq 和摘要版本。用户记忆以明确指令为依据，保存来源消息和作用域；AI 推测不自动升级为项目规则。执行检查点使用结构化记录，不从摘要推断业务操作是否已经发生。

事件条目不注入模型历史。已中断的工具调用必须恢复或补齐明确取消/失败结果，再被后续模型上下文使用。业务数据保持最新权威来源；历史摘要不覆盖当前字段。

第一版自定义 MySQL 消息仓库。可以适配 Spring AI ChatMemory 接口，但工具配对、摘要和执行恢复由 Runtime 统一管理，避免另一套 Advisor 自动裁剪工具历史。

## 8. 持久化设计

核心采用六张表，沿用 BaseEntity、雪花 ID 和逻辑删除约定。业务扩展的 shot_production_plan、agent_material_requirement 在后续阶段加入。

| 表 | 关键字段与约束 |
| --- | --- |
| agent_config | agentCode、displayName、description、enabled、providerId、modelCode、systemPrompt、modelOptionsJson、toolPolicyJson、skillPolicyJson、memoryPolicyJson、runtimePolicyJson、avatarConfigJson、revision、profileVersion；一个 agentCode 仅有一条有效配置 |
| agent_session | agentCode、scopeJson、summary、coveredThroughSeq、nextSeq、activeRunId、显示标题 |
| agent_message | sessionId、runId、seq、messageType、modelRole、contentJson、toolCallId、clientRequestId、readAt；唯一(sessionId, seq)，用户请求唯一(sessionId, clientRequestId) |
| agent_run | sessionId、runType、objective、configRevision、profileSnapshotJson、状态、当前检查点、预算用量、finalResultJson、取消标记、leaseOwner、leaseUntil、leaseEpoch、版本 |
| agent_step | runId、logicalStepKey、stepType、工具及版本、providerCallId、参数/结果、waitHandle、状态、尝试次数、幂等键；唯一(runId, logicalStepKey) |
| agent_memory | 作用域类型/ID、memoryKey、内容、来源消息、版本、启用状态；按作用域与有效键查询 |

agent_message 承载两种用途：

- USER / ASSISTANT / TOOL_RESULT：模型可见的对话消息；助手的完整响应保存同轮全部 toolCalls。
- EVENT：持久化的操作进度、等待和终态条目，modelRole 为空，仅用于 UI 和回放。

agent_config 使用明确列保存查询字段，JSON 保存有类型 DTO 的嵌套配置，不直接接收任意 Map。revision 使用有界整数做乐观锁；保存携带 expectedRevision，冲突返回 CONFIG_VERSION_CONFLICT，页面提示重新加载。首版保存即生效，本地未保存表单不影响普通 Run。Run 快照保留当时的执行配置，首版不另外建设配置历史表或发布审批流程。

同一事务锁定 Session 并分配连续 seq；消息追加、Run/Step 状态变化及对应关键事件原子提交。seq 是有界递增整数，DTO 采用整数且校验边界；业务 ID 使用字符串，两者分别处理。

数据库保存稳定结果与检查点，完整模型响应保存后发布权威条目，首版不提供逐 Token 预览。未来增加流式预览时使用临时片段，不逐 Token 写库；回放仍以已持久化内容为准。

## 9. 恢复、取消与执行预算

- 提交用户消息和创建 Run 使用 clientRequestId 幂等。相同请求返回原 messageId/runId，避免网络重试重复启动。
- 调度器在短事务中认领 QUEUED 或到期租约，增加 leaseEpoch。每次写入检查状态、租约代次和取消标记，旧执行线程不能继续提交修改。
- RUNNING 状态中断后按 Step 恢复：只读可重放，本地写读取已提交结果，外部操作先 reconcile。
- WAITING_TASK 通过终态事件和周期扫描推进。WAITING_USER 仅由明确回答恢复，不能因时间流逝视为同意。
- 取消请求先持久化，再中断可中断的模型/工具调用。已提交业务写入保留，未开始工具生成取消结果；终态不得被迟到响应覆盖。
- 实际无法确认外部取消时，保存 upstreamCancelStatus，前端报告已停止后续操作及上游状态，避免声称远程作业已终止。
- 并发 Session、模型轮数、工具调用数、上下文大小、运行时间和重试次数均可配置。用户等待与外部等待单独计时，避免空等占用执行预算。
- Token 按实际响应累加；未返回时标记未知。模型失败重试与业务写操作重试使用不同策略。

恢复能力按上述工具契约实现，不承诺所有外部服务天然提供恰好一次执行。

## 10. API 与运行中的输入

所有 HTTP 返回沿用 R<T>，提交后立即返回 ID，推理与工具执行在后台完成。

| 方法与路径 | 用途 |
| --- | --- |
| POST /agent/sessions | 创建会话，指定 agentCode 与可选项目 |
| GET /agent/sessions | 会话列表与当前活动状态 |
| GET /agent/sessions/{id}/messages?afterSeq= | 加载持久化消息与事件 |
| POST /agent/sessions/{id}/messages | 提交目标、调整指令或回答，返回消息和 Run ID |
| GET /agent/runs/{id} | 查询状态、检查点和结果 |
| POST /agent/runs/{id}/cancel | 请求取消 |
| POST /agent/runs/{id}/resume | 重试当前会话最后一个失败 Run；等待用户时通过 ANSWER 消息推进 |
| POST /agent/runs/{id}/read | 标记终态通知已读 |
| GET /agent/profiles | 列出已启用且有效的 Agent、能力要求及名称/形象等展示配置 |

配置管理接口集中在 /system/agent，详见 11.1；形象与其他 Agent 配置由同一服务保存，不另建 /agent/avatar-config 写入来源。

消息请求示例：

~~~json
{
  "clientRequestId": "5b901307-5a10-4d23-9896-228aacd3a23e",
  "intent": "START_RUN",
  "content": "查看当前项目有哪些人物资产",
  "providerId": "2032095628944588801",
  "modelCode": "configured-chat-model"
}
~~~

intent 明确为 START_RUN / STEER / ANSWER。活动 Run 下的新任务请求返回 ACTIVE_RUN_EXISTS，首版不排队多条同会话目标；STEER/ANSWER 必须携带 expectedRunId，防止输入误落到已切换的任务。

START_RUN 默认使用配置页保存的模型；providerId/modelCode 是可选的本次覆盖，必须成对提供且满足 Profile 能力要求，覆盖结果写入 Run 快照，不改写配置。STEER/ANSWER 不能借此更换当前 Run 的模型。

STEER 在下一模型或工具安全边界生效，更新目标约束版本；已经提交的操作记录保留。影响正在进行的推理时丢弃过时结果再重算，写事务校验目标约束版本。无法在既有作用域执行的补充指令返回明确问题。

## 11. 实时协议与前端

复用任务中心 WebSocket 连接，增加 AGENT_SUBSCRIBE、AGENT_UNSUBSCRIBE 和 AGENT_EVENT 分支；Agent 消息序列独立于旧 AI 任务事件，不改变其已有回放协议。

稳定事件示例：

~~~json
{
  "event": "AGENT_EVENT",
  "data": {
    "sessionId": "2032095628944588802",
    "runId": "2032095628944588803",
    "seq": 18,
    "type": "RUN_COMPLETED",
    "payload": {
      "summary": "已读取项目人物清单。",
      "finished": true
    }
  }
}
~~~

事件包括 RUN_STARTED、MODEL_STARTED、TOOL_STARTED、TOOL_COMPLETED、WAITING_TASK、WAITING_USER、MESSAGE_SAVED、RUN_COMPLETED、RUN_FAILED、RUN_CANCELLED。稳定事件以 agent_message 的持久化 seq 回放。TEXT_DELTA 为后续预览协议，首版未发送。

断线重连按 afterSeq 回放。订阅端先建立实时缓冲，再读取持久化高水位和缺失条目，合并并去重，避免回放与实时接收之间漏消息。状态和终态事件在事务提交后推送；漏发可通过 HTTP 或重连补齐。

前端新增：

- store/agent.ts：Session、活动 Run、条目、临时文字、游标和已读通知。
- AgentChatDrawer.vue：会话历史、输入、工具进度、继续与取消。
- AgentAvatarWidget.vue：全局入口和 IDLE / THINKING / WORKING / SUCCESS / ATTENTION / ERROR 精灵图状态动画。
- AgentRunCard.vue：通用运行结果；业务 Profile 后续扩展自己的结果卡片。
- views/system/agent/index.vue：独立 Agent 配置管理页，路由 /system/agent，侧栏“管理 → Agent 配置”。

用户提供 Codex Pet ZIP 包（pet.json 与静态透明 PNG/WebP 精灵图）。默认 FREYJA 支持九种标准动作，入口为约 224 × 243 CSS 像素的透明无边框宠物，点击切换聊天窗口。完成气泡只在持久化终态事件到达后出现，点击定位对应会话；关闭聊天不取消 Run，取消使用显式业务按钮。

### 11.1 独立 Agent 配置页

配置页是基础架构的首版交付内容。页面使用左侧 Agent 列表、右侧配置表单。列表显示名称、启停状态、默认模型和配置是否有效；首版只有 core-assistant，后续业务模板自动进入同一列表。切换 Agent 时提示处理未保存修改，保存按钮显示当前版本和保存结果。

| 分区 | 可配置内容 | 交互与边界 |
| --- | --- | --- |
| 基本信息 | 显示名称、描述、启用/停用 | agentCode 与模板类型只读；停用时说明已有任务仍继续 |
| 模型配置 | 提供商、CHAT 模型、支持的 temperature/topP/maxTokens | 复用模型中心级联选择；提供商和模型 ID 保持字符串；API Key 在现有提供商页面维护 |
| 系统指令 | 可编辑职责说明、工作偏好、回复风格 | 展示模板固定执行约束；编辑指令不能绕过工具范围、作用域或完成校验 |
| 工具与 Skills | 可选工具勾选、可选 Skill、是否允许按需加载 | 从后端目录选择，显示用途、读写类型和作用域；必需项只读，工具实现和 Schema 不在页面编写 |
| 记忆与上下文 | 摘要开关、近期历史窗口、上下文预算、SESSION/PROJECT 规则读取范围 | 配置记忆策略；调整不删除已有消息和规则，预算内保留完整工具配对 |
| 执行参数 | 模型轮数、工具调用数、执行时限、模型调用超时、外部等待时限、重试次数、Agent 并发上限 | 展示模板默认值及系统上限；等待计时沿用 Runtime 规则，不由前端计算运行终态 |
| 虚拟形象与通知 | Codex Pet ZIP、形象显示开关、完成/失败/需处理气泡开关 | 上传 ZIP、恢复默认、下载默认包、九行动作预览；加载失败回退默认宠物 |

页面顶部提供“保存配置”“校验配置”和“重新加载”。配置页没有测试面板。上传 ZIP 校验通过后复用 MediaAssetService 将精灵图原始字节上传到 MinIO，配置保存访问 URL/资源标识。未保存的宠物只用于表单预览，保存后刷新对应 Agent 的展示配置。拖动位置、用户主动收起等个人展示偏好保存在前端，不作为所有会话共用的执行配置。

形象协议采用 Codex Pet v1/v2：每格 192 × 208、8 列，v1 为 9 行，v2 为 11 行。前九行分别为 idle、running-right、running-left、waving、jumping、failed、waiting、running、review；v2 方向帧保留但当前页面不消费。上传组件只接受 ZIP，上限 20 MB；后端不落盘解压，校验清单、相对路径、条目数、展开体积、静态 PNG/WebP 画布和透明通道，仅归档被清单引用的精灵图。

前端通过 CSS 背景位置和标准逐帧停留时间播放，使用不同精灵行表示运行状态；同状态重复进度不重启动画。尊重系统减少动画设置。加载失败回退默认宠物，默认图也失败时使用入口图标。任务完成与气泡显示以持久化事件为准，不等待动画结束。宠物层级高于非模态聊天窗口，同时低于配置弹窗和选择器。

avatarConfigJson 示例：

~~~json
{
  "visible": true,
  "pet": {
    "id": "freyja",
    "displayName": "FREYJA",
    "description": "银白发、紫金花饰的 Q 版创作助手",
    "spritesheetUrl": "/agent/freyja/spritesheet.webp",
    "contentType": "image/webp",
    "spriteVersionNumber": 1,
    "frameWidth": 192,
    "frameHeight": 208,
    "columns": 8,
    "rows": 9
  },
  "stateMedia": {},
  "notifications": { "completed": true, "failed": true, "needsAttention": true }
}
~~~

stateMedia 仅兼容旧配置和历史快照，前端不再消费 GIF。新上传地址来自 MediaAssetService；恢复默认使用随前端发布的内置包。通知开关只控制气泡，不影响任务结果持久化、聊天进度或未读状态。

模型下拉只列出启用提供商的启用 CHAT 模型。有工具的 Agent 必须使用支持 Tool Calling 的模型；paramsJson.capabilities.toolCalling 明确为 false 时不可选择，未声明时显示“能力待验证”，需在模型中心确认能力，不默认视为支持。模型中心维护该能力声明；保存时检查模型归属、状态、类型和已知能力，运行时仍处理真实协议错误。

工具和 Skill 选择受 Profile 上限约束。动态加载只允许配置范围内已启用的 Skill，Run 固定具体发布版本；不能通过提示词临时加载任意工具。制作 Profile 接入后，其目录只包含准备工作的能力，不包含图片生成和视频渲染工具。

配置保存校验覆盖必填字段、枚举、JSON DTO、模型归属、必需工具/Skill、版本可用性、参数范围和预算。停用配置允许暂不选择模型；启用配置缺少必要项时拒绝保存并返回字段级问题。摘要关闭后若完整历史无法放入上下文预算，返回明确上下文限制，不静默丢弃未配对的工具记录。

配置 API：

| 方法与路径 | 用途 |
| --- | --- |
| GET /system/agent/configs | 列出已注册 Agent 的保存配置摘要及校验状态 |
| GET /system/agent/configs/{agentCode} | 读取表单、revision、模板默认值和固定约束 |
| PUT /system/agent/configs/{agentCode} | 校验并保存配置，携带 expectedRevision，返回新 revision |
| GET /system/agent/configs/{agentCode}/catalog | 读取该模板允许的工具、Skill 和字段约束 |
| POST /system/agent/configs/{agentCode}/validate | 校验当前表单，返回字段错误/提示，不保存 |
| POST /system/agent/configs/{agentCode}/test-runs | 内部隔离验收接口，页面不提供测试入口 |

AgentConfigDTO 按 model、instructions、tools、skills、memory、runtime、avatar 分组，独立于数据库 JSON 字段命名。前端读取完整表单后使用 PUT 保存整份配置；可清空字段传 null，必填或枚举字段不能传空字符串。expectedRevision 是整数，providerId、Skill ID、上传资源 ID 等业务 ID 为字符串。

内部验收 API 使用实际模型，可传入通过校验的配置快照；配置页面已移除测试面板和相关文案。复用 Runtime、消息、进度和取消机制，agent_run.runType 为 CONFIG_TEST，测试会话独立于普通项目会话，测试快照同时记录来源配置版本和实际表单内容。测试只允许配置内的只读工具与 Skill 加载，不执行资产创建、分镜修改或记忆写入；没有项目作用域时只启用无需项目的工具。测试期间不读取/写入真实项目记忆，不发送全局完成气泡，也不修改已保存配置。保存会话笔记等写工具在正常会话的基础功能验收中验证。

校验通过说明静态配置合法。内部验收分别检查模型回答和真实工具调用记录；普通文本回答不能视为 Tool Calling 验证成功，业务完成仍由完成策略验收。

## 12. 后续业务扩展方式

新增制作 Agent 时注册 production-preparation Profile，提供：

1. Project/Shot/SourceText 等 ContextContributor。
2. 剧本拆解、幂等入库、文字资产创建、分镜方案应用等 AgentTool。
3. 分镜准备 CompletionPolicy：检查模式、关联、有效图片与待补需求。
4. 制作结果 DTO 和前端结果卡片。

该 Profile 使用同一 AgentConfigService 和 /system/agent 配置页，展示制作专属的工具与 Skill 选项；会话、模型、记忆、预算和形象设置沿用基础层协议。

AgentRun 的 COMPLETED 与分镜 READY / WAITING_ASSET_IMAGES 是不同概念，由业务输出明确体现。基础层无需出现 generationMode、ResKeyframe 等制作专有字段。

Skill 目录、核心工具定义和业务事实分别组织，按 Profile 注册并固定版本；后续可扩展更多业务 Profile，无需另建一套会话和恢复系统。

## 13. 建议代码目录

~~~text
com.astra.freyja
  agent
    profile       AgentProfile / AgentProfileRegistry
    settings      AgentConfigService / AgentConfigValidator / AgentConfigInitializer
    runtime       AgentRuntime / AgentExecutionScheduler / AgentRecoveryScheduler
    model         ModelTurnReply / ToolCall / ToolResult / WaitHandle
    gateway       AgentModelGateway / SpringAiAgentModelGateway
    tool          AgentTool / AgentToolRegistry / AgentToolExecutor
    context       AgentContextBuilder / ContextContributor
    memory        AgentMemoryService / AgentMemorySummarizer
    policy        AgentCompletionPolicy / AgentBudgetPolicy
    event         AgentEventPublisher
    integration   task-center / skills / existing-service adapters
  entity          AgentConfig / AgentSession / AgentMessage / AgentRun / AgentStep / AgentMemory
  dao             corresponding MyBatis-Plus mappers
  dto/agent       requests / views / event payloads
  controller      AgentController / AgentConfigController
  config          AgentRuntimeConfiguration
~~~

前端配置页位于 front/src/views/system/agent/index.vue；配套 api/agent-config.ts、types/agent-config.ts 与页面分区组件。front/src/router/index.ts 注册 AgentConfig 路由，front/src/layout/Sidebar.vue 增加管理菜单项。AgentConfig 是业务实体，Spring 容器配置类使用 AgentRuntimeConfiguration，避免同名混淆。

避免同时建设通用 DAG 引擎与 AgentRuntime。首版检查点围绕模型调用、工具调用和等待状态即可；复杂固定业务流程通过已有 Service 作为工具执行。

## 14. 实施顺序与验收

| 阶段 | 产出 | 验收重点 |
| --- | --- | --- |
| A：配置、会话与模型 | 六张表、Profile/ConfigService、独立配置页与菜单、模型/指令/形象表单、消息 API、Gateway、基础聊天与测试 | 可从配置页选择模型并启用 Agent，测试得到真实回答，刷新后配置和历史恢复，ID 无精度丢失 |
| B：工具闭环 | 注册器、执行器、结构化错误、完成策略，配置页工具与 Skill 目录及测试结果 | 模型读取项目并保存会话笔记，结果可查询，越界参数拒绝，页面不能添加未注册工具 |
| C：持久执行 | 幂等、检查点、租约、异步等待、取消与扫描 | 重启不重复写入，外部结果未知先查询，迟到响应不覆盖取消 |
| D：记忆与界面 | 摘要、贡献者、记忆设置、WebSocket、形象气泡和任务中心 | 配置页各分区完整生效，长会话工具配对有效，断线回放完整，通知只出现一次 |

第一阶段最终验收应包含真实模型调用与可控测试适配器两部分：

- 从“管理 → Agent 配置”进入独立页面，设置模型、工具、预算和形象，保存后刷新仍保留；停用 Agent 不能新建普通任务。
- 修改配置后，新 Run 使用新 revision，活动 Run 和重启恢复保留原执行快照；形象保存后展示立即更新。
- 两个页面同时保存配置时，旧版本得到明确冲突，不覆盖新设置；无效模型、未注册工具和超出上限的参数被拒绝。
- Codex Pet v1/v2 ZIP 可导入、保存和重新加载；默认包可下载后重新上传。宠物透明无边框、点击开关聊天、拖动播放移动动画；缺失或损坏精灵图回退默认。
- 配置页没有测试面板和相关文案。内部验收接口仍支持隔离验证，真实普通会话可验证记忆写入；文本回答不误报为工具调用成功。
- 用真实配置的 CHAT 模型发起任务，实际选择读取工具，得到结果并完成。
- 相同 clientRequestId 连续提交只创建一个 Run；同会话并发目标有明确冲突处理。
- 模型返回多工具调用后，响应落库再执行；工具结果能正确反馈模型。
- 在写入提交后模拟进程中断，恢复读取原结果；在外部提交响应丢失时走 reconcile。
- 等待异步任务期间关闭聊天和重启服务，完成后继续同一目标。
- 中途 STEER 保留正确作用域，并使过时推理和应用失效。
- 取消后新操作停止，旧租约线程和迟到响应不能修改终态。
- 摘要保留关键规则，跨项目记忆隔离，工具调用/结果不被裁剪成不完整序列。
- 丢失 WebSocket 连接和 Redis 缓存后，从 MySQL 重建状态与稳定事件。
- 气泡对应真实终态，点击打开正确会话，已读通知不重复。

实现时遵守根 AGENTS.md：JDK 26 + Maven Wrapper 验证后端，指定 Node 运行前端构建，保留 Boot 4 的 MyBatis-Plus Starter、Lombok 配置和 Snowflake 字符串传输。基础 Agent 代码不加入任何自然语言关键词推断业务状态的逻辑。
