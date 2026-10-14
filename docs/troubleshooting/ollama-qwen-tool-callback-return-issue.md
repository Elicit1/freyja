# 本地 Ollama Qwen 27B 章节拆解失败问题分析与排查备忘

## 1. 问题现象与现场还原

### 1.1 业务场景
在工作台执行「整章剧情并行分镜拆解」（第一步：Planner AI 宏观剧情结构分析与分段规划）时任务中断失败。

### 1.2 错误提示与日志事实
1. **前端错误卡片提示**：
   ```text
   Planner AI 章节分段解析失败: No ToolCallback found for tool name: return
   ```
2. **Ollama 后端控制台日志**：
   ```text
   Oct 14 11:58:47 admin1-MS-7D25 ollama[1962]: slot print_timing: id 0 | task 7180 | n_decoded = 6589 ...
   Oct 14 11:58:47 admin1-MS-7D25 ollama[1962]: prompt eval time = 811.09 ms / 161 tokens (5.04 ms per token, 198.50 tokens per second)
   Oct 14 11:58:47 admin1-MS-7D25 ollama[1962]: eval time = 597118.34 ms / 6625 tokens (90.13 ms per token, 11.09 tokens per second)
   Oct 14 11:58:47 admin1-MS-7D25 ollama[1962]: total time = 597929.44 ms / 6786 tokens
   Oct 14 11:58:47 admin1-MS-7D25 ollama[1962]: [GIN] 2026/10/14 - 11:58:47 | 200 | 9m58s | 172.19.0.4 | POST "/api/chat"
   ```
3. **关键客观数据**：
   - 模型在本地运行了整整 **9 分 58 秒**（597 秒）。
   - 本地推理吞吐约为 **11.09 tokens/s**。
   - 模型实际完成了 **6625 个 tokens** 的生成，Ollama 接口返回了 `200 OK`；这些 token 可能包含正文、Thinking 内容或 Tool Call 参数，不能仅凭计时日志认定全部都是可直接解析的业务正文。
   - 报错发生在 Ollama 响应返回给 Java 后端 Spring AI 解析执行的那一瞬间。

---

## 2. 深度根因分析 (Root Cause)

### 2.1 触发链路：Tools 注入机制
1. 在 `ChapterDecompositionServiceImpl.java` 中，Planner AI 阶段强制要求加载 `character-disambiguation`（角色消歧技能）。
2. 在 `ScriptSkillRuntime.java` 中构建 Invocation 时：
   ```java
   ToolCallback readFileTool = !session.getLoadedSkillNames().isEmpty() || stage.isAllowDynamicLoad()
           ? readSkillFileToolFactory.createTool(...) : null;
   ```
   只要必用技能预加载成功，`!session.getLoadedSkillNames().isEmpty()` 便为 `true`，导致 `readFileTool` 始终会被实例化并注入。
3. `skillInvocation.hasTools()` 判定为 `true`，于是 Spring AI 的 `ChatClient` 在调用时附带了 `requestSpec.tools(...)`。
4. 发往 Ollama 的请求因此携带了 `tools` 字段，**强制激活了 Ollama 与 Qwen 模型的 Tool Calling（函数调用）模式**。

### 2.2 模型输出与 Ollama 解析机制（高概率推断）
1. Qwen 系列模型（包括 Qwen 2.5 / Qwen 3 27B）经过 Tool Calling 训练，支持基于 ChatML / `<tool_call>` 语法的函数调用。
2. 当输入整章超长小说时，Planner 需要生成极其庞大的长文本 JSON（包含剧本分段、角色、场景、道具）。
3. 本地中型开源模型（27B）在长时间生成长文本的末尾，极易受到 system 中 tools 协议的干扰，产生**结束格式幻觉**：
   - 误认为需要通过类似 Python 的 `return(...)` 函数将最终结构化数据交回复命；
   - 或者在输出的结尾产生了类似 `return { ... }` / `<tool_call>{"name": "return", ...}</tool_call>` 的标记；
4. Ollama 内置的 Tool Call 解析器在扫描模型输出时，将这一输出判定为一个工具调用，工具名称提取为 `"return"`，并封装在 `message.tool_calls` 中返回给调用方。

> 目前可以确认的事实是 Spring AI 收到了名为 `return` 的 Tool Call；“模型为何生成该调用”以及该调用参数中是否包含完整业务 JSON，仍需保存 Ollama 原始响应后才能最终确认。因此本节机制属于与现场高度吻合的推断，不应作为已经取得原始响应证据的结论。

### 2.3 Spring AI 异常崩溃点
1. 后端 Spring AI 接收到 Ollama 返回的 200 响应后，检测到 `tool_calls` 存在且请求调用工具 `"return"`。
2. Spring AI 的 `ToolCallingManager` 在当前注册工具白名单中检索（当前仅注册了 `read_skill_file` 和 `load_skill`）。
3. 未找到名为 `"return"` 的 `ToolCallback` 实例，Spring AI 抛出未捕获的致命异常：
   ```java
   IllegalStateException: No ToolCallback found for tool name: return
   ```
4. 该异常被外层捕获并封装为 `BizException("Planner AI 章节分段解析失败: No ToolCallback found for tool name: return")`，导致本轮已生成内容未能进入 Planner 的业务 JSON 解析与校验流程。

---

## 3. 架构缺陷与隐患总结

1. **预加载与 Tool Calling 边界模糊**：
   - 必用 Skill（如 `character-disambiguation`）的正文已经完整内嵌在 System Prompt 中，模型只需要阅读参考，绝大多数情况下不需要在生成过程中再去调用 `read_skill_file`；
   - 仅因预加载了 Skill 就默认向模型开启 Tool Calling，使得不支持/不擅长 Tool Calling 的本地模型或中小型模型被强行拖入工具模式，增加幻觉风险。
2. **Spring AI 工具回调缺乏未知工具容错兜底**：
   - 开源模型在长文本生成或复杂任务中极易产生不存在的工具名调用（如 `return`、`final_result`、`submit` 等）；
   - 框架直接抛出 `IllegalStateException` 崩溃，缺乏将伪工具调用参数回退解析为纯文本内容的容错熔断。
3. **本地模型长文本推理耗时高**：
   - 27B 本地速度约 11 tokens/s，生成 6600+ tokens 耗时接近 10 分钟。如果模型开启了 Thinking（思维链）或者输出了冗余内容，很容易因超时或格式意外导致高成本失败。
4. **前端默认配置会持续触发 Tool Calling**：
   - `ScriptDecomposeModal.vue` 当前将 `plannerDynamicSkills` 和 `workerDynamicSkills` 都初始化为 `true`，关闭弹窗重置时也恢复为 `true`；
   - 因此即使修正“必用 Skill 预加载错误创建 `read_skill_file`”的问题，默认请求仍会因为 `allowDynamicLoad=true` 注册 `load_skill` 和 `read_skill_file`，无法真正消除默认场景下的 Tool Calling 风险。

---

## 4. 推荐修改方案

### 4.1 第一层：彻底分离“必用 Skill 预加载”和“运行时工具调用”（必须实施）

Planner 依赖的 `character-disambiguation` 属于必用指导规范，其 `SKILL.md` 已在模型调用前加载并追加到 System Prompt。仅使用必用 Skill 时不依赖 Tool Calling，也不应向模型发送任何 `tools` 定义。

在 `ScriptSkillRuntime.begin(...)` 中，将 `read_skill_file` 的创建条件由：

```java
ToolCallback readFileTool = !session.getLoadedSkillNames().isEmpty() || stage.isAllowDynamicLoad()
        ? readSkillFileToolFactory.createTool(...) : null;
```

改为：

```java
ToolCallback readFileTool = stage.isAllowDynamicLoad()
        ? readSkillFileToolFactory.createTool(...) : null;
```

修改后的配置语义应保持清晰且可验证：

| 阶段配置 | 必用 Skill 正文 | `load_skill` | `read_skill_file` | 请求是否携带 `tools` |
| --- | --- | --- | --- | --- |
| 有必用 Skill，`allowDynamicLoad=false` | 调用前注入 System Prompt | 不注册 | 不注册 | 否 |
| `allowDynamicLoad=true` | 必用正文照常注入 | 注册 | 注册 | 是 |

这样 `Invocation.hasTools()` 只表示本次调用确实需要运行时 Tool Calling。纯预加载模式继续走 `chatModel.stream(prompt)`，不会激活 Ollama/Qwen 的工具协议。

如果某个必用 Skill 的 `SKILL.md` 指示必须读取 `references/` 文件，创作者需要显式开启 `allowDynamicLoad`；不能因为 Skill 已预加载就隐式开启工具能力。

### 4.2 第二层：默认关闭 Planner/Worker 动态加载（必须实施）

前端当前默认开启动态加载，使所有新建拆解任务默认携带工具。应在 `front/src/views/drama/components/ScriptDecomposeModal.vue` 中同时修改初始化值和弹窗重置值：

```ts
const plannerDynamicSkills = ref(false)
const workerDynamicSkills = ref(false)
```

```ts
plannerDynamicSkills.value = false
workerDynamicSkills.value = false
```

用户确实需要模型在执行中自行选择其他 Skill 或读取 `references/` 时，再显式打开“按需调用”。后端现有的 `validateToolCapability(...)` 继续负责在动态加载开启时校验模型声明的 Tool Calling 能力；仅使用必用 Skill 的任务不受该能力限制。

### 4.3 第三层：未知工具调用采用通用协议容错（后续增强）

不应注册名为 `return`、`result`、`final_answer` 的伪工具，也不应按工具名称猜测模型意图。该方式会把协议错误伪装成正常工具执行，可能继续触发下一轮模型生成，同时违反项目禁止关键字/字符串猜谜的工程原则。

如需回收本轮昂贵的生成结果，应在工具编排层实现与具体名称无关的处理：

1. 聚合完整的 `AssistantMessage`，读取 Tool Call 名称和原始参数；
2. 已注册工具按白名单正常执行；未知工具绝不执行；
3. 对 Planner/Worker 这类无业务副作用的结构化输出阶段，只有同时满足以下条件时，才把未知调用的参数作为候选模型输出：
   - 本轮恰好存在一个未知 Tool Call；
   - 本轮没有已执行的真实工具调用；
   - 普通正文为空或不含另一个相互冲突的业务结果；
   - Tool Call 参数能够直接通过目标 DTO 的完整 JSON Schema、字段约束和业务校验；
4. 校验成功后仍进入现有 `parseAndValidate(...)` 流程；校验失败则抛出明确的“模型调用了未注册工具”协议错误，并记录工具名、参数长度和内容摘要用于排查。

这项增强不能通过简单的 `try/catch IllegalStateException` 完成，因为当前 `ChatClient.tools(...).stream().content()` 会在 Spring AI 内部工具执行阶段抛错。若要取得未知 Tool Call 的原始参数，应改用可检查完整 `ChatResponse` 的显式工具循环，或提供等价的自定义 Tool Calling 编排层。

### 4.4 模型调用参数优化（辅助措施）

- 对确认支持 Thinking 开关的 Ollama 模型，可在模型 `paramsJson` 中设置 `"think": false`。当前 `AiModelFactoryImpl.applyOllamaExtras(...)` 已支持该配置，无需在 Planner 业务代码中按模型名称硬编码。
- 可以按模型实际能力设置合理的 `maxTokens/num_predict`，但不建议直接统一限制为 4000~6000。Planner 的合法结构化 JSON 可能超过该长度，过低上限会把未知工具问题变成稳定的 JSON 截断问题。
- 保持 Planner Prompt 的紧凑输出约束，不复制章节全文，减少无效生成；参数优化只能降低概率和耗时，不能替代 Tool Calling 边界修复。

---

## 5. 修改位置

| 文件 | 修改内容 |
| --- | --- |
| `src/main/java/com/astra/freyja/skill/service/ScriptSkillRuntime.java` | 仅在 `allowDynamicLoad=true` 时创建 `load_skill`、`read_skill_file` 并进入 `ChatClient` 工具通道 |
| `front/src/views/drama/components/ScriptDecomposeModal.vue` | Planner/Worker 动态加载初始化和重置默认值改为 `false` |
| `src/test/java/com/astra/freyja/skill/ScriptSkillRuntimeTest.java` | 修正“仅必用 Skill”用例，并保留动态工具注册用例 |
| Planner/Worker Ollama 协议测试 | 验证动态加载关闭时发往 Ollama 的请求体不存在 `tools`，开启时只包含允许的工具定义 |

---

## 6. 回归测试与验收标准

1. `requiredSkillNames` 非空、`allowDynamicLoad=false` 时：
   - 必用 Skill 正文仍出现在 System Prompt；
   - `Invocation.hasTools()` 返回 `false`；
   - `readSkillFileToolFactory` 不被调用；
   - 请求通过普通 `ChatModel.stream(prompt)` 发出，Ollama 请求体不含 `tools`。
2. `allowDynamicLoad=true` 时：
   - `load_skill` 与 `read_skill_file` 都被注册；
   - 模型明确声明 `capabilities.toolCalling=false` 时，任务在模型调用前被拒绝；
   - 已注册工具仍可完成调用、回传结果并继续生成最终 JSON。
3. 新打开或重置拆解弹窗时，Planner/Worker 的“按需调用”开关均为关闭状态；恢复历史任务时仍使用任务草稿中保存的原策略。
4. Planner 强制预加载 `character-disambiguation` 的约束保持不变，关闭动态加载不能绕过 Skill 校验或正文注入。
5. 如果后续实现未知工具容错：未知调用不得被执行；只有其参数完整通过目标 DTO 校验时才允许作为候选业务输出，否则任务应返回清晰的协议错误。

---

## 7. 实施优先级

1. **P0**：修正 `ScriptSkillRuntime` 的工具创建条件，并更新对应单元测试；
2. **P0**：将前端 Planner/Worker 动态加载默认值改为关闭；
3. **P1**：增加 Ollama 请求体协议测试，防止后续再次把纯预加载任务带入 Tool Calling；
4. **P2**：根据实际失败频率决定是否实现通用未知 Tool Call 结构化回收；
5. **P2**：按具体 Ollama 模型能力调整 Thinking 与输出预算。
