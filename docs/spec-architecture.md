spec_content = """# 🎬 AI 短剧分镜管理与 ComfyUI 调度系统 - 完整工程设计与开发规范文档

> 本文档汇总了系统的全套架构设计、数据库全量 DDL（含 BaseEntity 字段）、通用数据字典机制、多 AI 提供商动态工厂、ComfyUI 异步调度引擎及双轨剪辑归档规范，可直接作为 OpenCode 开发任务书使用。

---

## 目录
1. [系统定位与技术栈](#1-系统定位与技术栈)
2. [系统整体架构与数据流](#2-系统整体架构与数据流)
3. [BaseEntity 与全量数据库 DDL](#3-baseentity-与全量数据库-ddl)
4. [通用数据字典模块规范](#4-通用数据字典模块规范)
5. [多 AI 提供商与模型动态工厂](#5-多-ai-提供商与模型动态工厂)
6. [Spring AI 剧本智能拆解与角色提取](#6-spring-ai-剧本智能拆解与角色提取)
7. [ComfyUI 异步调度与 WebSocket 进度反馈](#7-comfyui-异步调度与-websocket-进度反馈)
8. [双轨存储与本地剪辑工程归档规范](#8-双轨存储与本地剪辑工程归档规范)
9. [Vue 3 前端工程与组件交互规范](#9-vue-3-前端工程与组件交互规范)
10. [OpenCode 实施任务清单 (Task Checklist)](#10-opencode-实施任务清单-task-checklist)

---

## 1. 系统定位与技术栈

### 1.1 系统定位
针对 AI 短剧生产制作全周期，打造“**四层剧作树状结构 + 角色/场景一致性资产库 + 动态大模型剧本拆解 + ComfyUI 异步视频生成队列 + 本地分层剪辑目录自动同步**”的工业化系统。

### 1.2 技术选型清单
> 以下表格以当前项目 `pom.xml` 实际引入的依赖为准。

| 层次 / 组件 | 选用技术 | 版本 / 说明 |
| :--- | :--- | :--- |
| **后端框架** | Spring Boot (`spring-boot-starter-parent`) | 4.1.1 (JDK 26) |
| **Web 框架** | Spring Web MVC (`spring-boot-starter-webmvc`) | 版本由 Spring Boot 管理 |
| **数据缓存** | Spring Data Redis (`spring-boot-starter-data-redis`) | Redis 7.0+ (字典缓存 + 生成任务 Stream 队列) |
| **关系型数据库** | MySQL (`mysql-connector-j`) | 8.0+ (InnoDB, utf8mb4)，运行时依赖 |
| **持久层框架 (DAO)** | MyBatis + MyBatis-Plus (`mybatis-plus-spring-boot4-starter`) | 3.5.17，支持 BaseEntity 自动填充与逻辑删除 |
| **AI 框架** | Spring AI (`spring-ai-bom` + `spring-ai-openai` / `spring-ai-ollama` + `spring-ai-client-chat`) | 2.0.1，支持 OpenAI 规范、动态 ChatModel 与 ChatClient Tool Calling |
| **对象存储** | MinIO Java SDK (`io.minio:minio`) | 8.5.17 (媒体生成产物分层持久化与自动归档；AI Skills 正文不再写入对象存储) |
| **生成引擎通信** | Spring WebSocket + Apache HttpClient 5 | `StandardWebSocketClient` (ComfyUI WS 监听) + `RestClient` (HTTP 调度) |
| **视频末帧抽取** | ffmpeg (外部系统工具, `freyja.ffmpeg.path`) | 视频产物归档时抽取末帧，供镜头组内「末帧 → 下一镜首帧」视觉续接 |
| **工具库** | Apache Commons Lang3 (`commons-lang3`)、SnakeYAML (`snakeyaml`) | 版本由 Spring Boot 管理（字符串处理、标准 Skill frontmatter 解析与校验） |
| **测试** | `spring-boot-starter-data-redis-test` / `spring-boot-starter-webmvc-test` | 测试作用域 |

> 以下组件属于设计规划，尚未在 `pom.xml` 中引入依赖（规划中）：

| 层次 / 组件 | 选用技术 | 版本 / 说明 |
| :--- | :--- | :--- |
| **前端工程** | Vue 3 + Vite + Pinia | Composition API + TypeScript |
| **UI 组件库** | Element Plus / Naive UI + Tailwind CSS | 统一封装 `<DictSelect />` |

---

## 2. 系统整体架构与数据流

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        Vue 3 前端 (Vite + Pinia)                        │
│  ├─ 剧作大纲树状工作台 (短剧 -> 剧集 -> 情节 -> 分镜卡片流)             │
│  ├─ 角色/场景资产抽屉 (LoRA / FaceID / 参考图绑定)                     │
│  ├─ ComfyUI 实时渲染监视看板 (WebSocket 进度条推送)                   │
│  └─ 系统配置中心 (多 AI 提供商管理、字典维护、存储路径设置)            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ HTTP RESTful / WebSocket
┌───────────────────────────────────▼────────────────────────────────────┐
│                    Spring Boot 3.x 后端业务核心                        │
│                                                                        │
│  ┌──────────────────────┐  ┌────────────────────────────────────────┐  │
│  │   Spring AI 智能引擎 │  │         ComfyUI 异步调度引擎           │  │
│  │  - DynamicChatFactory│  │  - Redis Stream 任务队列与并发控制     │  │
│  │  - 剧本解析 & 角色对齐│  │  - Java WebSocket Client (实时进度监听)│  │
│  │  - BeanOutputConverter│ │  - Workflow JSON 动态参数注入器       │  │
│  └──────────────────────┘  └────────────────────────────────────────┘  │
│  ┌──────────────────────┐  ┌────────────────────────────────────────┐  │
│  │    通用数据字典引擎   │  │             双轨资产归档服务           │  │
│  │  - Redis 缓存自动同步│  │  - MinIO Client (云端对象存储与预览)   │  │
│  │  - 前端 <DictSelect> │  │  - LocalFileArchiver (本地分层工程落盘)│  │
│  └──────────────────────┘  └────────────────────────────────────────┘  │
└──────────────┬───────────────────┬───────────────────┬─────────────────┘
               │                   │                   │
    ┌──────────▼──────────┐ ┌──────▼──────┐ ┌──────────▼──────────┐
    │  LLM (DeepSeek /    │ │    MySQL    │ │   ComfyUI Server    │
    │   OpenAI / Ollama)  │ │   + Redis   │ │ (API & WebSocket)   │
    │     (Spring AI)     │ │   + MinIO   │ │                     │
    └─────────────────────┘ └─────────────┘ └─────────────────────┘

---

> **文档完整性说明**：以下第 3 章起为根据本文件保留的第 1-2 章技术栈/架构描述重建的规范。重建部分仅还原 BaseEntity、通用数据字典与骨架所需设计，完整业务 DDL 与各模块细节将在对应模块开发时逐步补全。

## 3. BaseEntity 与数据库通用字段约定

所有业务表统一继承 `BaseEntity`（`com.astra.freyja.entity.BaseEntity`），由 MyBatis-Plus 自动填充与逻辑删除。

| 字段 | 类型 | 说明 | 填充策略 |
| :--- | :--- | :--- | :--- |
| `id` | BIGINT | 主键，雪花算法 / 自增 | 自动 |
| `create_by` | BIGINT | 创建人 ID | 插入时自动填充 |
| `create_time` | DATETIME | 创建时间 | 插入时自动填充 |
| `update_by` | BIGINT | 更新人 ID | 插入/更新时自动填充 |
| `update_time` | DATETIME | 更新时间 | 插入/更新时自动填充 |
| `deleted` | TINYINT | 逻辑删除 0/1，`@TableLogic` | 自动 |
| `remark` | VARCHAR(500) | 备注 | 手动 |

> 自动填充由 `MyMetaObjectHandler`（`com.astra.freyja.config`）实现；所有表均为 `InnoDB` + `utf8mb4`，主键 `BIGINT UNSIGNED`。

## 4. 通用数据字典模块规范

采用"类型 + 数据项"两表设计，Redis 缓存自动同步，供前端 `<DictSelect>` 下拉使用。

- `sys_dict_type`：字典类型（`dict_type` 编码唯一、`dict_name`、状态、备注）
- `sys_dict_data`：字典数据项（`dict_type` 归属、`dict_label`、`dict_value`、排序、状态）

缓存 Key 约定：`freyja:dict:{dict_type}`，值为有序数据项列表；`DictService` 负责查库、回填缓存、失效刷新。

> 说明：本骨架先落地字典两表结构与基础链路，业务字典项在后续模块添加。

## 5. 多 AI 提供商与模型动态工厂

### 5.1 表结构（详见 `src/main/resources/sql/schema.sql`）

- `ai_provider`：提供商配置。`provider_code` 唯一；`provider_type` ∈ `OPENAI`（OpenAI 规范兼容，含 DeepSeek）/ `OLLAMA`；`api_key` **AES-GCM 加密密文**（密钥来自 `freyja.crypto.key`，接口返回掩码 `sk-****1234`）；含超时、重试、熔断参数。
- `ai_model`：模型配置，`provider_id` 外键挂提供商，`(provider_id, model_code)` 唯一；含 `model_type`（CHAT/IMAGE/EMBEDDING）、`temperature`/`max_tokens`/`top_p` 及 `params_json` 额外参数。

### 5.2 缓存 Key 约定

- `freyja:ai:provider:enabled`：启用中的提供商列表。
- `freyja:ai:model:{providerId}`：某提供商启用中的模型列表。
- 写操作后自动失效对应缓存；`AiModelFactory` 的模型实例为内存缓存（`ConcurrentHashMap`），配置变更时 `evict`。

### 5.3 动态工厂 AiModelFactory

`com.astra.freyja.service.AiModelFactory`（实现 `AiModelFactoryImpl`）按数据库配置动态构建 ChatModel 并缓存：

- OPENAI 类型 → `OpenAiChatModel.builder().options(OpenAiChatOptions...)`，baseUrl/apiKey/超时/重试/采样参数全部来自配置，apiKey 读取时解密；
- OLLAMA 类型 → `OllamaApi.builder().baseUrl(...)` + `OllamaChatModel.builder().ollamaApi(...).options(OllamaChatOptions...)`，baseUrl 缺省 `http://localhost:11434`；
- 外层包裹 `CircuitBreakerChatModel` 熔断装饰器：连续失败达 `breaker_threshold` 后 `breaker_timeout` 秒内 fail-fast（抛 `BizException`），到期自动恢复；重试由 `max_retries` 透传 Spring AI 底层。

业务模块注入 `AiModelFactory` 取 `ChatModel`（`chatModel.call(...)` / `chatModel.stream(...)`）即可，无需关心具体提供商。

### 5.4 接口文档

详见 `docs/ai-provider-api.md`（提供商/模型 CRUD、下拉列表、连通性测试、掩码与缓存约定）。

## 6. Spring AI 剧本智能拆解与角色提取

### 6.1 核心设计与数据链路
基于 **Spring AI (`BeanOutputConverter`)** 配合 `@JsonPropertyDescription` 注解自动生成 JSON Schema，驱动任意 OpenAI 规范或 Ollama 本地模型（DeepSeek / GPT-4o / Claude / Qwen 等）完成剧本智能影视化拆解：
1. **多维提取**：提取短剧大纲、出场角色资产（含角色定位、外貌/服饰画图 Prompt 与触发词）、环境场景（含空间类型、时段、天气与生图 Prompt）、分集大纲与带**说话人及台词对白**的逐镜分镜卡片流。
2. **资产自动对齐与去重**：自动与现有 `res_character` 和 `res_scene` 库进行同名模糊匹配，前端可直观选择复用已有资产或创建新资产。
3. **级联持久化**：支持前端审查看板在线编辑微调，一键写入短剧四层树状结构（`drama` -> `drama_episode` -> `drama_scene` -> `drama_shot`），并自动建立分镜角色引用 `character_refs_json`。
4. **接口规范**：详见 `docs/script-decompose-api.md`。

## 7. ComfyUI 异步调度与 WebSocket 进度反馈

详见 `docs/comfy-render-api.md`。

## 8. 双轨存储与本地剪辑工程归档规范

详见 `docs/components.md` 与 MinIO / 本地归档实现。

## 9. Vue 3 前端工程与组件交互规范

- 通用数据字典维护 (`/system/dict`)
- AI 提供商与模型管理 (`/system/ai-provider`)
- 角色与场景资产管理及 Prompt 演练台 (`/assets`)
- 剧作大纲与可视化分镜卡片流工作台 (`/drama`)，包含「⚡ AI 一键剧本智能拆解」全功能弹窗 (`ScriptDecomposeModal.vue`)

## 10. 实施进度

- [x] 基础设置：后端可运行骨架（配置 / BaseEntity / 字典表 / 统一返回 / 全局异常）
- [x] 通用数据字典模块（类型+数据项、Redis 缓存、CRUD 接口）
- [x] AI 提供商与模型动态工厂模块（提供商/模型配置、AES 加密与掩码、AiModelFactory 动态构建、熔断装饰器）
- [x] 角色与场景资产管理及 Prompt 组装引擎模块
- [x] 短剧、剧集、情景与分镜四层大纲工作台及 ComfyUI 渲染调度模块
- [x] Spring AI 剧本智能拆解、分集分场景与角色对话提取模块
- [x] Vue 3 前端全套业务视图与 AI 剧本拆解工作台弹窗
