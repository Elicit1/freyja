# Agent 基础架构：部署、使用与接口

更新日期：2026-10-11。第一阶段已实现通用 `core-assistant`，包括独立配置页、持久化会话与执行循环、明确记忆、任务中心进度、聊天窗口及 Codex Pet 精灵图虚拟形象与 ZIP 导入。

系统现在只提供一个 `core-assistant`，聊天窗口显示为 **Freyja agent**，统一承担小说创作、多问题需求澄清、查询、明确记忆，以及小说拆解入库、文字资产整理、分镜模式与参考素材选择、提示词准备和缺图报告。不提交生图或视频渲染任务。业务约束见 [制作设计](agent-production-preparation-design.md)。

旧 `production-preparation` 标识仅用于兼容已有会话、接口和运行快照，不再作为独立配置展示。首次读取旧版配置时按版本自动合并制作能力：优先保留原 FREYJA 助手的模型、名称和宠物，合并已启用的工具、技能与指令，并补齐制作所需的执行和上下文预算；没有主配置时继承旧制作配置。旧配置记录、会话、Run 快照和制作结果保留，新任务统一使用主配置。后续停用助手或修改预算不会再次被旧制作配置恢复。

合并后的部署与真实模型验收见 [单助手验收记录](agent-single-assistant-validation.md)。

## 1. 数据库升级

新数据库使用已更新的 Docker 初始化脚本。**已有数据库只执行增量文件 `src/main/resources/sql/agent-foundation.sql`，不要执行完整 `init.sql`，后者会重建既有业务表。** 增量文件只创建六张新表，不修改已有业务字段或记录。

也可使用项目已有迁移脚本环境（Python + PyMySQL）：

```powershell
# 只读预检查，默认使用 docs/components.md 的本地连接设置
python scripts/migrate_agent_foundation.py
# 仅创建缺少的 Agent 表
python scripts/migrate_agent_foundation.py --apply
```

脚本支持 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_DATABASE` 环境变量覆盖。重复执行不会重建表；已有 Agent 表缺少当前必需列时会停止并报告，避免跳过不完整升级。

新增表：`agent_config`、`agent_session`、`agent_message`、`agent_run`、`agent_step`、`agent_memory`。没有增加运行时依赖或向量数据库。MySQL 是消息与检查点的权威来源，首版 Agent 事件通过后端进程内发布到既有 WebSocket，不依赖 Redis 缓存恢复。

第二阶段已有数据库使用 `scripts/migrate_agent_production.py`（默认只读预检查，`--apply` 执行），或增量 SQL `src/main/resources/sql/agent-production-preparation.sql`。新增 `agent_preparation_job`、`shot_production_plan`、`agent_material_requirement` 三表，将 `res_keyframe.frame_url` 改为可空。增量迁移不删除已有数据；新数据库初始化脚本包含两个阶段的表。制作拆解复用 Redis 草稿，并将原文、请求、完整预览及子任务检查点持久化到 MySQL。

## 2. 启用与试用

1. 在 AI 提供商中心配置并启用提供商与 CHAT 模型。模型须支持工具调用；`paramsJson.capabilities.toolCalling=false` 会阻止带工具的配置，未声明时页面提示验证。
2. 打开“管理 → Agent 配置”（`/system/agent`），选择模型，调整指令、工具、Skills、记忆和执行参数。
3. 保存后点击页面悬浮宠物进入聊天；再次点击关闭。配置页仅包含配置与形象预览。
4. 保存并启用助手。首次配置默认停用，应用升级不会覆盖已有设置。
5. 点击全局 Agent 入口打开聊天，新建通用或项目会话。例如：“列出这个项目的人物资产”“读取分镜 ID … 的信息”“请记住：本项目采用写实风格”。

配置分为基本信息、模型、系统指令、工具与 Skills、记忆、执行参数、虚拟形象七个分区。保存使用 `expectedRevision` 乐观锁；配置冲突返回 `409 / CONFIG_VERSION_CONFLICT`。已有 Run 使用创建时的配置、指令、工具和 Skill 版本快照；更新配置只影响新 Run。形象配置保存后立即刷新展示。

模型分区的“回复方式”支持 `model.outputMode=STREAM`（流式输出）或 `COMPLETE`（完整回复）。新安装默认流式；旧配置和旧 Run 快照缺少该字段时保持完整回复，升级不覆盖用户模型及其他设置。保存后对新 Run 生效，包括“停止并发送”创建的新任务；现有任务与用户回答后的续跑保持原快照。历史摘要始终使用完整调用，不显示摘要生成过程。

形象使用 Codex Pet v1/v2 标准包。`avatar.pet` 保存 id、displayName、description、spritesheetUrl、contentType、objectPath、spriteVersionNumber、frameWidth、frameHeight、columns、rows。前九行驱动待机、移动、招手、完成、失败、等待、工作与思考动画。旧 `stateMedia` 仅供历史 JSON 读取，新配置清空该字段并使用默认宠物。

配置页支持“上传 ZIP”“恢复默认”和下载默认包，保存后立即生效。ZIP 上限 20 MB；仅归档清单引用的静态透明 PNG/WebP 精灵图，拒绝不安全路径和解压超限。单格 192 × 208，v1 8×9，v2 8×11。悬浮宠物约 224 × 243 CSS 像素，透明无边框；点击切换非模态聊天，拖动播放左右移动动作，任务气泡进入对应会话。素材说明见 [Agent 形象](agent-avatar/README.md)。

开启虚拟形象、上传宠物或恢复默认后保存，会同时解除本浏览器的收起状态；在“虚拟形象”页签直接保存也会展开已开启的宠物。即使修改形象后切换到其他页签保存，仍会展开。平时手动收起或展开的状态在刷新后保留，保存其他配置不会解除收起。关闭形象或停用助手时显示圆形聊天入口，不显示无效的“展开/收起”按钮；重新启用并保存后恢复宠物。

## 3. 执行与记忆约定

- 一个 Session 同时只允许一个活动 Run。用户请求用 `(sessionId, clientRequestId)` 幂等，重试返回原消息和任务 ID。
- 完整模型回答与工具调用先保存，再执行工具。工具结果保存后才进行下一模型轮次。未经授权或参数无效的调用返回结构化错误。
- 首版按顺序执行模型返回的工具，基础工具为 `get_project_overview`、`list_project_assets`、`get_shot_detail`、`save_session_note`、`list_session_notes`、`load_skill`、`read_skill_file`。资产查询返回每类最多 50 个候选。
- 查询的项目作用域由会话确定，读取具体分镜时校验其项目归属。业务 ID 在 HTTP 和工具结果中使用字符串，前端保持字符串；`seq`、配置版本与计数使用整数。
- `save_session_note` 只保存用户明确要求记住的会话/项目规则，引用本次 Run 的真实 USER 消息。支持同键替换和 `enabled=false` 撤回，禁止写入未启用的记忆作用域。第一版通过聊天工具管理明确记忆，没有独立记忆编辑页。
- 上下文保留完整的助手/工具结果配对，历史过长时生成摘要并原子保存覆盖游标。摘要用于历史理解，业务事实仍以当前数据库为准。上下文 Token 为保守估算，实际用量以提供商返回值累加；未提供用量时显示未知。
- Run 租约、代次与取消标记约束每次检查点写入。取消阻止后续操作和迟到结果写入，保留已经提交的数据；失败 Run 可重试，同一会话已有后续任务时需新建目标继续。
- 工具恢复模式为 `REPLAY_SAFE`、`TRANSACTIONAL`、`RECONCILE`。本地写入与结果检查点共用事务；外部提交结果未知时先按原幂等键查询，避免重复提交。
- `DEFERRED` 保存首次截止时间并进入 `WAITING_TASK`，释放执行线程；首版每约 5 秒扫描查询恢复。`NEED_USER` 进入 `WAITING_USER`，由用户回答推进。基础工具没有外部提交，协议由测试工具验证，为后续业务工具保留接口。
- 当前面向单后端实例部署：全局并发上限默认为 4，可用系统参数 `agent.runtime.maxConcurrentRuns` 在 1–4 内调整；每个 Agent 另有并发上限。首版没有跨实例事件广播和集群并发协调。

## 4. HTTP 接口

返回沿用 `R<T>`；业务 Long ID 序列化为字符串。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/system/agent/configs` | 配置列表、惰性初始化默认配置 |
| GET / PUT | `/system/agent/configs/{code}` | 读取/保存配置 |
| GET | `/system/agent/configs/{code}/catalog` | 工具、必需工具、Skills 与固定约束 |
| POST | `/system/agent/configs/{code}/validate` | 校验当前表单 |
| POST | `/system/agent/configs/{code}/test-runs` | 内部验收接口（页面无测试面板），输入 `config/content/clientRequestId/dramaId?` |
| POST | `/system/agent/avatar/upload` | Multipart `file` 导入 Codex Pet ZIP，返回 `avatar.pet`；保存配置后生效 |
| GET | `/agent/profiles` | 已启用且校验有效的助手及展示配置 |
| POST / GET | `/agent/sessions` | 创建/列出正式会话 |
| GET | `/agent/sessions/{id}` | 会话详情和活动 Run |
| GET | `/agent/sessions/{id}/messages?afterSeq=0` | 消息与事件，按 seq 升序，每页最多 500 条 |
| POST | `/agent/sessions/{id}/messages` | 提交新目标、停止并发送、补充要求或回答 |
| GET | `/agent/runs/{id}` | 状态、用量和结果；不返回内部快照及租约 |
| GET | `/agent/runs/{id}/report` | 最终结构化摘要 |
| POST | `/agent/runs/{id}/cancel` | 取消并中断本地执行 |
| POST | `/agent/runs/{id}/resume` | 重试当前会话最后一个失败 Run |
| POST | `/agent/runs/{id}/read` | 标记终态通知已读 |
| GET | `/agent/notifications` | 未读的正式终态任务 |

创建会话：

```json
{"agentCode":"core-assistant","dramaId":"2032095628944588801","title":"项目助手"}
```

提交消息：

```json
{"clientRequestId":"a-unique-request-id","intent":"START_RUN","content":"列出当前项目的人物资产"}
```

返回 `sessionId/messageId/runId`，模型与工具在后台执行。`START_RUN` 可成对提供 `providerId/modelCode` 作为本次模型覆盖；`STEER` 与 `ANSWER` 必须携带 `expectedRunId`，活动任务不能更换模型。`ANSWER` 只用于 `WAITING_USER`，不是 `/resume` 的替代参数。

聊天中的“补充要求”继续同一个 Run，等待中的旧模型结果按约束版本检查后丢弃，不会立即中断正在等待返回的模型调用。“停止并发送”使用 `REPLACE_RUN`，必须携带字符串 `expectedRunId`；服务端先校验新任务配置，再在同一会话锁和事务内取消旧 Run、封闭未完成工具调用并创建新 Run。旧任务取消与新目标接收同时提交，新 Run 使用最新配置，保留原会话、记忆和已提交的业务数据。提交后中断旧任务的本地执行，制作子任务也会结束；上游接口没有取消确认时不承诺远端请求已停止。

```json
{"clientRequestId":"another-unique-request-id","intent":"REPLACE_RUN","expectedRunId":"2109286509711650817","content":"停止原来的故事，改为回答新问题"}
```

重复请求返回原 `sessionId/messageId/runId`；任务标识过期返回 `409 / RUN_CHANGED`，不会停止另一个任务。新配置无效时保留原活动任务。Enter 保持原有发送、补充或回答语义，替换任务由“停止并发送”显式触发；提交上一条消息期间输入的新文本不会被清空。前端只在终态 Run 与当前活动 Run 相同时清除会话活动状态，避免旧取消通知覆盖新任务。

主要状态为 `QUEUED`、`RUNNING`、`WAITING_TASK`、`WAITING_USER`、`COMPLETED`、`FAILED`、`CANCELLED`。`PARTIAL_SUCCESS` 保留给业务 Profile；取消在数据库中直接提交 `CANCELLED`。

## 5. 实时事件

复用 `/ws/task-center`：

```json
{"event":"AGENT_SUBSCRIBE","sessionId":"2032095628944588801","afterSeq":0}
```

取消订阅：`AGENT_UNSUBSCRIBE`，携带同一个字符串 `sessionId`。服务端发送：

```json
{"event":"AGENT_EVENT","data":{"sessionId":"2032095628944588801","runId":"2032095628944588802","seq":3,"type":"MESSAGE_SAVED","payload":{"id":"2032095628944588803","sessionId":"2032095628944588801","runId":"2032095628944588802","seq":3,"messageType":"ASSISTANT","body":{"content":"回答正文","toolCalls":[],"properties":{}}}}}
```

`payload` 是权威消息视图。事件包括 `RUN_QUEUED/RUN_STARTED/MODEL_STARTED/MODEL_RETRY/TOOL_STARTED/TOOL_COMPLETED/WAITING_TASK/WAITING_USER/MESSAGE_SAVED/USER_INPUT_SAVED/RUN_COMPLETED/RUN_FAILED/RUN_CANCELLED`。事务提交后推送，订阅先读取固定高水位再回放，前端按连续 seq 合并去重，断线后从游标补齐。

流式模式使用同一 WebSocket 的独立临时事件，绝不占用持久消息 `seq`：

```json
{"event":"AGENT_STREAM","data":{"sessionId":"2032095628944588801","runId":"2032095628944588802","modelStepId":"2032095628944588803","streamId":"a-request-local-uuid","sequence":1,"type":"SNAPSHOT","text":"正在逐步生成的公开回复"}}
```

`text` 是本次尝试的累计公开文字，最多每约 100ms 推送一次；`sequence` 仅在同一 `streamId` 内递增。内部推理、工具参数与元数据不进入预览。工具参数由适配器合并，整轮流式完成后才保存模型回复、累计用量并执行工具。请求超时或中断会释放流式订阅；缺少结束标记的响应进入重试，不保存半截回复。

预览仅驻留当前后端内存，收到 `CLOSED` 清除；模型重试、补充要求、取消、任务替换、租约换代以及最终 `ASSISTANT` 消息落库都会清除旧预览。补充要求会停止展示旧推断，但继续收取结果以记账，随后按新要求调用模型。订阅/重连在持久事件回放之后补发当前预览快照；完整回复落库后以 `modelStepId` 对齐并替换预览，不重复展示。后端进程重启后沿既有检查点恢复，不回放已丢弃的半截文字。完成后的历史和两种模式一致。

任务中心通过 `AGENT_RUN` 展示摘要，`COMPLETED` 映射为任务中心的 `SUCCESS`；点击任务可定位聊天中的对应 Run。

## 6. 验证与业务扩展

```powershell
$env:JAVA_HOME='F:\program_file\jdk26'
.\mvnw.cmd test
# 可选的真实 MySQL 检查：须先执行增量迁移；测试夹具和配置均回滚，不调用真实 AI
.\mvnw.cmd '-Dtest=com.astra.freyja.agent.*Test,TaskCenterAgentReplayTest' '-Dfreyja.agent.db-test=true' test
$env:PATH='D:\program_file\mvn\nvm\v24.9.0;' + $env:PATH
cd front
npm run build
```

新增业务能力接入统一助手的有类型 `AgentTool`、`ContextContributor` 和完成检查，不为每项业务新增一个用户可配置的 Agent。工具必须声明项目约束、效果、版本和恢复模式，写入工具不能使用默认只读重放策略。制作能力通过这些接口调用既有拆解、资产和规划 Service，无需改写通用 Runtime。

## 7. 小说转视频准备

在 Agent 配置页为助手设置支持 Tool Calling 的 CHAT 模型并启用。直接聊天即可创作、续写或润色小说，文本保存在会话中；无需开启制作流程。原“小说制作准备”入口保留，更名为“小说转视频准备”，用于将已有原文准备成可制作的视频分镜。点击该入口填写原文、项目、风格和制作要求。可新建项目，也可在已有项目中追加新剧集；不会覆盖旧剧集。提交返回后可关闭聊天，后台继续处理。

聊天窗口在屏幕中央显示，保持非模态，桌面点击宠物仍可开关；新建会话与制作表单也是居中弹窗，模态弹窗覆盖聊天层。窄屏使用自适应宽高，聊天层覆盖宠物避免遮挡输入，使用右上角关闭按钮返回宠物入口。历史与问题卡片在窗口内滚动，出现待回答问题时自动定位卡片。

### 7.1 多问题需求澄清

`request_user_input` 是统一助手的必需工具。AI 判断哪些不确定项影响结果，输出结构化问题，每次 1–3 题；每题可提供 2–3 个互斥选项与至多一个推荐选项，或使用空选项数组作为自由填写题。界面始终支持自定义回答，没有自动选择或超时确认。回答后继续同一 Run，后续可再次提问；原有纯文本回答和“停止并发送”保留。

工具参数示例：

```json
{"questions":[{"id":"genre","header":"题材","question":"希望创作什么题材的小说？","options":[{"label":"奇幻","description":"以想象世界为背景","recommended":true},{"label":"科幻","description":"以未来科技为背景","recommended":false}]},{"id":"hero","header":"主角","question":"主角有什么特点？","options":[]}]}
```

问题和工具检查点持久化后，`WAITING_USER` 事件携带字符串 `questionStepId` 和 `questions`。刷新页面、关闭聊天或重启服务后可重建问题卡片。结构化回答仍提交到原消息接口：

```json
{"clientRequestId":"unique-answer-id","intent":"ANSWER","expectedRunId":"2032095628944588802","content":"题材：奇幻\n主角：喜欢星空","questionStepId":"2032095628944588803","answers":[{"questionId":"genre","optionLabel":"奇幻"},{"questionId":"hero","customAnswer":"喜欢星空"}]}
```

后端验证当前 Run、等待的工具步骤、完整题目集合和合法选项；每题选择或自定义回答二选一。有效回答以服务器生成的文本保存到用户消息，同时将结构化回答保存为配对工具结果。过期问题返回 `409 / QUESTION_CHANGED`，过期 Run 返回 `409 / RUN_CHANGED`。重复 `clientRequestId` 返回原提交结果。未回答时不占执行线程或主动超时恢复。

Profile v2→v3 自动补齐提问工具并升级配置版本，保留模型、名称、形象、启停状态、可选工具和用户预算，不再次合并旧制作配置。已运行的历史 Run 保持原快照。真实模型与界面验收见 [提问与小说创作验收](agent-questions-validation.md)。

### 7.2 制作流程与接口

流程为：拆解 → 完整结果入库 → 资产盘点 → 缺少资产保存文字 → 每镜模式与槽位规划 → 素材齐全时生成最终提示词 → 保存交付报告。模型选择 `FIRST_LAST_FRAME` 或 `REFERENCE_MODE` 并给出理由；服务端校验项目、造型归属、关键图用途、主基准互斥和最多 9 张参考图。首帧模式先创建真实 `FIRST_FRAME` 文字关键图；缺图/过时造型图不进入 `refImagesJson`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/agent/production/start` | 提交原文；`clientRequestId` 幂等 |
| GET | `/agent/production/runs/{id}/report` | 制作交付快照，尚未交付返回 null |
| GET | `/agent/production/shots/{id}/plan` | 当前规划、版本、指纹、锁和准备状态 |
| POST | `/agent/production/refresh` | 指定 `dramaId/shotIds`，读取最新素材并安全刷新，单次最多 100 镜 |
| PUT | `/agent/production/shots/{id}/locks` | `expectedRevision/fields`，锁定或解锁规划字段 |

`start` 输入包含 `clientRequestId/sourceText`，可选 `dramaId/dramaTitle/chapterTitle/instruction/stylePreset/styleTone`，默认 `aspectRatio=9:16`、`pacingPreset=AUTO`。原文最长 100000 字；同标识的不同内容返回 409。所有业务 ID 均为字符串。

制作工具为 `start_script_decomposition`、`retry_decomposition_segment`、`commit_decomposition`、`list_preparation_shots`、`get_reference_candidates`、`ensure_text_assets`、`save_shot_production_plans`、`prepare_shot_prompts`、`inspect_preparation`、`get_preparation_report`。每页最多 12 镜，每批保存最多 10 镜；候选目前最多 200 个角色及其造型、200 场景、200 道具和 200 关键图。公共库只读复用，模型通过真实 ID 选择。

实际模型执行建议每批处理 1–4 项文字资产、保存 1–3 镜详细规划，避免工具 JSON 超过输出预算；参数截断时应缩小批次。

`ensure_text_assets` 可用 `existingId + updateText=true + description + prompt` 补齐本项目原资产的文字，保留 ID、图片和其他设定，由既有资产服务处理图文一致性；公共库不能修改。拆解生成的默认造型若缺少描述，`inspect_preparation` 返回 `incompleteTextAssets`，模型必须补齐文字并重新查询指纹后继续规划。交付资产清单按类型和 ID 去重。近期消息数量是保留偏好，工具结果过大时会自动减少完整轮次，以满足上下文与输出预算。

`COMPLETED` 表示准备任务交付，分镜 `WAITING_ASSET_IMAGES` 表示文字方案已保存且待补图；这可以是正常交付。`READY` 还要求有效图片和对应当前输入的提示词。`NEEDS_REFRESH` 表示输入或素材改变，`PENDING/ERROR` 不能交付。最终回答前重新核对当前规划与报告版本，防止旧报告掩盖并发修改。

报告展示模式、依据、资产清单和按资产身份去重的补图需求，可直接跳转具体分镜或资产。在现有资产编辑页生成或上传图片后，点击“刷新准备状态”，再用“补齐后更新提示词”创建指定分镜的后续任务。历史报告保持原交付快照，当前状态单独显示。手工修改分镜内容、模式、关联或提示词后，刷新不会覆盖，需要明确重新规划。锁定字段保持当前值，Agent 修改时受版本与指纹约束。

关键图创建允许省略图片 URL，保存 `TEXT_DRAFT`，需要描述或提示词。更新省略 `frameUrl` 保留旧图片，显式 `clearFrameUrl=true` 清空；后续上传沿用原资产 ID。

拆解、Worker 重试和提示词子任务采用事务提交后启动，恢复复用同一子任务 ID。取消或失败停止尚在运行的子任务；已提交的项目、资产与分镜保留。DeepSeek 推理元数据仅用于多轮协议回传，保留原始空字段及工具配对，不展示在聊天 UI，也不通过 `extraBody` 追加重复 `messages`。

拆解启动事务未提交时，将已保存的 Redis 草稿任务标为 FAILED，并保存错误和结束事件，保留原文草稿。同一任务在 Redis 和 MySQL 中同时存在时，先以 Redis 最新状态覆盖检查点，再过滤状态与去重，防止旧 PENDING 检查点重新进入活跃列表。取消事件已在提交后发布，子任务清理直接启动独立线程；清理覆盖 Redis 中的后代任务，并同步已有 SQL 检查点的终态。

真实页面验收和保留的示例数据见 [第二阶段验收记录](agent-phase2-validation.md)。

### 本次验证记录

2026-10-11：45 项 Agent 专项测试通过，包括 OpenAI/Ollama 实际 HTTP 协议适配（本地测试端点）、MySQL 工具闭环/取消/测试幂等，以及历史版本动态 GIF 在真实 MinIO 中的原始字节往返。前端 TypeScript 检查与生产构建通过。本机六张 Agent 表已用增量脚本创建，重复预检查没有缺表；数据库测试夹具回滚，测试 GIF 对象已清理。

完整后端回归共 482 项，478 项通过；以下 4 项在独立的未改动基线 `cb5c5cb` 中复现相同失败，属于既有问题：

- `ResCharacterLookServiceTest.testCreate_firstLook_autoSetDefault`：测试夹具缺少当前必需的视觉描述。
- `ResCharacterLookServiceTest.testUpdate_mismatchedCharacterId_throws400`：既有错误断言不符合当前校验顺序。
- `PlannerCharacterDisambiguationTest.skillDocumentsRequestedNameAliasPronounAndAmbiguityCases`：测试期待的 classpath Skill 正文资源缺失。
- `PlannerCharacterDisambiguationTest.distributableSkillZipPassesTheExistingPackageValidator`：测试期待的 classpath Skill ZIP 资源缺失。

历史版本未调用真实模型；本次 DeepSeek 与浏览器验收结果见下方。


### Codex Pet 升级验收（2026-10-11）

Agent 专项测试共 50 项：46 通过、4 项需显式启用的持久化集成测试跳过；包括 ZIP 路径和解压边界、原始精灵图归档、v1/v2 及错误格式验证。前端 TypeScript 检查和生产构建通过。使用真实 DeepSeek `deepseek-flash` 在独立验收会话完成聊天、明确规则保存/读取/更新/撤回和取消，工具执行无错误。使用 Chrome 验证大号透明宠物、点击开关、拖动、九行动画、ZIP 导入保存重载及恢复默认；上传精灵图在真实 MinIO 中逐字节保持一致。

真实模型验收仅使用专用会话与虚构数据；虚构项目概要与分镜查询成功，临时项目、剧集、场次、镜头组和分镜均已删除。浏览器真实聊天观察到思考 → 完成 → 待机动画，关闭窗口后完成气泡可进入对应会话。报告保存在忽略的 `logs/agent-deepseek-report.json`、`logs/agent-synthetic-project-report.json`、`logs/agent-live-chat-report.json` 和 `logs/agent-pet-browser-report.json`。部署更新仅针对现有 WSL Docker 的前端和后端，数据服务保持原有挂载。
