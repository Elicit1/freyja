# AI 提供商管理接口文档 (ai-provider-api)

> 供前端及其他 AI 模块对接使用。基路径默认 `http://127.0.0.1:8080`（以实际后端端口为准）。
> 所有接口统一返回 `R<T>` 结构，详情见"统一返回结构"。

## 统一返回结构

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

- `code = 200` 表示成功；非 200 为失败，`msg` 为可展示给用户的错误信息。
- 参数/唯一性校验失败时返回 `code = 500`（`BizException` 默认错误码），`msg` 说明原因。
- 分页接口 `data` 为 MyBatis-Plus `Page` 结构：

```json
{
  "total": 32,
  "size": 10,
  "current": 1,
  "pages": 4,
  "records": []
}
```

## 安全约定

- `apiKey` **AES-GCM 加密后落库**（密文存 `ai_provider.api_key`），数据库不存明文。
- 所有查询接口返回的 `apiKey` 均为**掩码**（如 `sk-****1234`），绝不明文返回。
- 修改接口 `apiKey` 留空 = 保持不变；新增接口 `apiKey` 必填。

## 实体字段

### AI 提供商 ai_provider
| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| id | Long | 主键（雪花） |
| providerCode | String | 提供商编码，唯一（如 deepseek/openai/ollama） |
| providerName | String | 提供商名称 |
| providerType | String | 接入类型：`OPENAI`（OpenAI 规范兼容，含 DeepSeek）/ `OLLAMA` |
| apiKey | String | API Key，**加密密文，返回掩码** |
| baseUrl | String | Base URL（OLLAMA 缺省 `http://localhost:11434`） |
| timeout | Integer | 请求超时（秒），默认 30 |
| maxRetries | Integer | 最大重试次数，默认 3 |
| enableBreaker | Integer | 是否熔断 0-关 1-开，默认 1 |
| breakerThreshold | Integer | 熔断阈值（连续失败次数），默认 10 |
| breakerTimeout | Integer | 熔断恢复时间（秒），默认 30 |
| status | Integer | 状态 0-停用 1-启用 |
| remark | String | 备注 |
| createTime / updateTime | LocalDateTime | 创建/更新时间（自动填充） |
| createBy / updateBy | Long | 创建/更新人 ID（自动填充） |

### AI 模型 ai_model
| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| id | Long | 主键（雪花） |
| providerId | Long | 归属提供商 ID |
| modelCode | String | 模型标识，同提供商下唯一（如 deepseek-chat / gpt-4o / llama3） |
| modelName | String | 模型名称（展示用） |
| modelType | String | 模型类型 CHAT/TXT2IMG/IMG2IMG/TXT_IMG2IMG/TXT2VIDEO_FIRST_LAST/TXT2VIDEO_REF/TTS/LIP_SYNC/EMBEDDING，默认 CHAT |
| temperature | Double | 采样温度（可空，走模型默认） |
| maxTokens | Integer | 最大输出 Token 数（可空） |
| topP | Double | 核采样概率（可空） |
| paramsJson | String | 额外模型参数 JSON（OPENAI：frequency_penalty/presence_penalty/stop/seed；OLLAMA：top_k/repeat_penalty） |
| sortOrder | Integer | 显示排序，升序 |
| status | Integer | 状态 0-停用 1-启用 |
| remark | String | 备注 |
| createTime / updateTime | LocalDateTime | 创建/更新时间（自动填充） |
| createBy / updateBy | Long | 创建/更新人 ID（自动填充） |

---

## 1. AI 提供商管理

### 1.1 分页查询提供商
- **GET** `/ai/provider/page`
- Query 参数（均可选）：`pageNum`（默认1）、`pageSize`（默认10）、`providerName`（模糊）、`providerType`（等值）、`status`（等值 0/1）
- 返回：`R<Page<AiProviderVO>>`，按 `createTime` 倒序；`data.records[]` 中 `maskedApiKey` 为掩码、`hasApiKey` 标识是否已配置。

### 1.2 启用中的提供商列表（下拉）
- **GET** `/ai/provider/list/enabled`
- 说明：返回 `status=1` 提供商，按 `createTime` 倒序；优先读 Redis（Key `freyja:ai:provider:enabled`），未命中查库回填。
- 返回：`R<List<AiProviderVO>>`

### 1.3 提供商详情
- **GET** `/ai/provider/{id}`
- 返回：`R<AiProviderVO>`；不存在时 `msg` 提示"AI 提供商不存在"。

### 1.4 新增提供商
- **POST** `/ai/provider`
- Body：`{ providerCode, providerName, providerType, apiKey, baseUrl?, timeout?, maxRetries?, enableBreaker?, breakerThreshold?, breakerTimeout?, status, remark? }`
- 校验：`providerCode`、`providerName`、`providerType` 非空且类型 ∈ OPENAI/OLLAMA；`status` 必填；`providerCode` 全局唯一（重复报"提供商编码已存在"）；`apiKey` 必填并加密落库。数值字段缺省按默认值。
- 返回：`R<Void>`。

### 1.5 修改提供商
- **PUT** `/ai/provider`
- Body：同新增，另需 `id`；`apiKey` 留空表示保持不变。
- 校验：`id` 必传；唯一性校验排除自身。修改后自动失效缓存与工厂实例。
- 返回：`R<Void>`。

### 1.6 删除提供商
- **DELETE** `/ai/provider/{id}`
- 说明：仅逻辑删除提供商记录，不级联删除其下模型。删除后失效缓存与工厂实例。
- 返回：`R<Void>`。

### 1.7 连通性测试
- **POST** `/ai/provider/{id}/test`
- Query 可选：`modelCode`（缺省使用该提供商第一个启用中的模型）
- 说明：经动态工厂构建 ChatModel 发送极简请求，成功返回 200；失败 `msg` 携带原因（含熔断提示）。
- 返回：`R<Void>`。

### 1.8 提供商下启用模型列表（下拉/工厂）
- **GET** `/ai/provider/{id}/model/list`
- 说明：返回 `status=1` 且归属该提供商模型，按 `sortOrder` 升序；优先读 Redis（Key `freyja:ai:model:{providerId}`）。
- 返回：`R<List<AiModel>>`

---

## 2. AI 模型管理

### 2.1 分页查询模型
- **GET** `/ai/model/page`
- Query：`pageNum`、`pageSize`、`providerId`（等值）、`modelName`（模糊）、`modelType`（等值）、`status`（等值）
- 返回：`R<Page<AiModel>>`，按 `providerId`、`sortOrder` 升序。

### 2.2 模型详情
- **GET** `/ai/model/{id}` — 不存在时 `msg` 提示"AI 模型不存在"。

### 2.3 新增模型
- **POST** `/ai/model`
- Body：`{ providerId, modelCode, modelName, modelType?, temperature?, maxTokens?, topP?, paramsJson?, sortOrder?, status, remark? }`
- 校验：`providerId` 必传且提供商存在；`modelCode`、`modelName` 非空；`status` 必填；同提供商下 `modelCode` 唯一（报"该提供商下模型标识已存在"）。`modelType` 缺省 `CHAT`、`sortOrder` 缺省 0。写后失效该提供商缓存与工厂实例。
- 返回：`R<Void>`。

### 2.4 修改模型
- **PUT** `/ai/model` — `id` 必传；唯一性校验排除自身；若变更 `providerId` 会校验新提供商存在。写后失效缓存与工厂实例。

### 2.5 删除模型
- **DELETE** `/ai/model/{id}` — 写后失效缓存与工厂实例。

---

## 3. 动态工厂（后端使用说明）

`AiModelFactory`（`com.astra.freyja.service.AiModelFactory`）为内部接口，其他业务模块直接注入使用：

```java
ChatModel chatModel = aiModelFactory.getChatModel(providerId, modelCode);
String reply = chatModel.call("你好");
```

- 首次调用按数据库配置构建并缓存，配置变更（改/删提供商或模型）自动失效。
- 返回实例外层包裹熔断器：连续失败达到 `breakerThreshold` 后，`breakerTimeout` 秒内 fail-fast（抛出 `BizException`），超时后自动恢复。
- 重试次数由 `maxRetries` 透传给 Spring AI 底层客户端。
- 接口返回的 `AiProviderVO` 仅为配置展示，工厂内部自行解密使用 apiKey。

## 前端对接建议
（提供商下拉用 `GET /ai/provider/list/enabled`；模型下拉用 `GET /ai/provider/{id}/model/list`；管理页用分页+增删改；编辑回显用掩码 `maskedApiKey`，仅当用户重新输入新 Key 时提交 `apiKey`；成功以 `code == 200` 判断，失败弹 `msg`。）