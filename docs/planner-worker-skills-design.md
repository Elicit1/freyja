# Planner / Worker 使用 AI Skills 功能设计

## 目标与当前缺口

章节拆解的 Planner 负责整章事件分段及角色、场景、道具提取；Worker 负责各 Segment 的镜头剧本和分镜结构。两者目前都通过 `ChatModel.stream(prompt)` 调用模型，没有注册 `load_skill` 工具，也没有接收 Skill 选择参数。导演规划及部分提示词生成接口已有 `SkillCatalogService`、`SkillPromptContextService`、`LoadSkillToolFactory`，可作为基础，但不能直接把导演规划的调用代码复制到并行 Worker。

本功能提供两种独立能力：

1. **运行时按需加载**：向指定阶段提供已启用 Skill 的轻量目录与 `load_skill(name)` 工具，由模型在执行过程中决定是否调用。
2. **强制使用**：创作者分别为 Planner 和 Worker 指定必用 Skill。后端在调用模型前验证并加载精确版本的 `SKILL.md`，将正文放入该阶段的 system prompt。是否使用工具不影响必用 Skill 的生效。

保持现有职责边界：Planner Skill 不得要求它输出镜头；Worker Skill 不得要求它输出生图或运镜 Prompt。原文、创作者锁定项、业务 JSON 格式和资产编号约束始终优先。

## 请求与界面

在 `ScriptDecomposeRequestDTO` 增加可选 `skillPolicy`，两个阶段各自配置：

```json
{
  "skillPolicy": {
    "planner": {
      "allowDynamicLoad": true,
      "requiredSkillNames": ["story-structure"]
    },
    "worker": {
      "allowDynamicLoad": true,
      "requiredSkillNames": ["shot-writing"]
    }
  }
}
```

- 旧请求不带 `skillPolicy` 时维持现状：不提供工具，也不注入目录或正文。
- `allowDynamicLoad=false` 仍允许使用 `requiredSkillNames`：必用正文照常预加载，只关闭模型自行选择其他 Skill 的能力。
- 两份名单独立；Worker 的名单应用于每个 Segment。第一版不做“按 Segment 指派 Skill”，因为 Segment 在 Planner 运行后才产生。
- 重试单个 Worker 时默认继承父任务的 Worker 策略；`WorkerRetryDTO.skillPolicyOverride` 可明确覆盖这一次重试，且覆盖结果单独审计。
- 前端在拆解弹窗中增加「Planner 技能」和「Worker 技能」折叠区域，每区一个“允许 AI 按需加载”开关、一个复用 `SkillSelector` 的“必用 Skill”多选框。运行期间锁定设置；重试弹窗可以修改本次 Worker 的必用名单。
- 下拉只列当前已启用且有可用版本的 Skill，展示名称、描述和版本。提交时传 Skill `name`，不传数据库 ID。

## 执行流程

1. 父任务创建时读取一次启用目录，校验必用名称，并形成不可变的 `name → versionId/version/hash` 快照。目录提示词与工具白名单都从此快照生成，避免同一任务内版本漂移。将策略和快照存入父任务可恢复的载荷或专用元数据；只存版本标识与哈希，不重复存 Skill 正文。
2. Planner 使用自己的 `LoadSkillToolSession`。先把必用 Skill 正文加入 system prompt，再附可用目录；如果允许动态加载，给本次模型调用注册绑定该会话的 `load_skill`。不允许动态加载时不注册工具，也不展示可调用目录。
3. Planner 产出 Segment 后，为每个 Worker **单独**创建 `LoadSkillToolSession`，但全部引用同一不可变版本快照。Worker 在虚拟线程内按自己的策略准备 system prompt 和工具，不能共享可变的 session 或加载历史。
4. Worker 的一次局部自动重试创建新的会话，继续使用相同的版本快照；人工单段重试按父任务快照及可选覆盖策略执行。这样每次模型请求的审计边界清楚。
5. 每次成功/失败记录 `stage`、`segmentId`、`attempt`、Skill 名称、版本 ID、内容哈希、`REQUIRED` 或 `TOOL` 来源、工具调用结果及次数。结果摘要和任务详情显示实际加载记录，不信任模型自报。

必用 Skill 若不存在、已停用或正文为空，**在任何 AI 调用前**返回清晰的参数错误。动态加载指定的 Skill 不在任务快照中时由工具返回 `ERROR`，不能退回当前最新版。当前 `SkillContentServiceImpl.loadSkillVersion()` 存在缓存命中绕过启用状态检查的问题；实现时需统一“停用后已开始的任务是否仍可读取”的规则。建议停用只影响新任务，已开始任务可按已授权的版本快照继续，删除版本前需检查活跃任务引用。

## 模型调用、流式输出与容错

- 先校验所选聊天模型的 Tool Calling 能力。开启动态加载但模型明确不支持工具时，在启动任务前提示更换模型或关闭动态加载；仅使用必用 Skill 的预加载模式不依赖工具能力。
- Planner 与 Worker 目前直接使用 `ChatModel.stream(prompt)`，该路径没有工具回调。改为支持工具回合的调用封装，并以本项目实际 Spring AI 版本验证流式 Tool Calling。只把最终回答的文本 token 写入原有 `PLANNER` / Segment SSE 通道；工具参数、工具结果和 Skill 正文不能混入 JSON 输出。
- 另发结构化 `skill_event`，包含阶段、Segment、Skill、版本及状态。前端可显示“正在加载 / 已加载 / 失败”，但不暴露正文。
- 现有“流式异常后直接同步重试”可能在模型已经调用工具或输出部分文本后重复执行。改造时要把每次尝试作为独立会话，并清空/标记已推送文本；只有明确可安全重放的失败才自动重试。人工重试保留独立审计记录。
- 只有 `SKILL.md` 正文会被现有 `load_skill` 返回；Skill ZIP 中的 `references/`、`assets/`、`scripts/` 尚不能被 Planner/Worker 使用。如果这些资源需要运行时读取，下一期增加只读 `read_skill_file(name,path)`，路径限定在本次版本包内，禁止执行包内脚本。

## 需要修改的位置

| 层 | 修改点 |
| --- | --- |
| DTO / 任务 | `ScriptDecomposeRequestDTO`、`WorkerRetryDTO`、父任务快照及执行审计结构 |
| 调度 | `ScriptDecomposeServiceImpl` 在父任务边界构建并传递快照，人工重试恢复快照 |
| Planner | `ChapterDecompositionServiceImpl` 接入阶段策略、必用正文、目录和专属工具会话 |
| Worker | `ParallelShotGenerationServiceImpl` 在每个 Segment / attempt 创建独立会话并接入工具 |
| Skill 基础 | 扩展 `SkillPromptContextService`，从同一目录构造快照、目录提示词和必用正文；统一版本访问语义 |
| 前端 | `ScriptDecomposeModal.vue`、请求类型、SSE `skill_event` 展示与重试配置 |

## 验收

1. Planner 只开动态加载时可以调用目录中的 Skill；未调用时不会加载正文。Worker 同理，且不同 Segment 的调用记录彼此隔离。
2. 指定必用 Skill 后，即使模型一次工具都不调用，也能从审计记录证明对应版本正文已注入该阶段；缺失必用 Skill 在启动前失败。
3. 同一父任务的 Planner、所有 Worker 和自动重试使用固定版本。切换 Skill 当前版本不会让任务中途漂移；人工重试可恢复原版本与策略。
4. 流式输出仍可解析为原有 Planner / Worker JSON，SSE 事件不泄露 Skill 正文，不重复拼接失败尝试的文本。
5. 不支持 Tool Calling 的模型可运行“仅必用预加载”，开启动态加载时收到明确错误。旧请求及现有无 Skill 拆解行为保持兼容。
