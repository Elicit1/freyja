# AGENTS.md

Spring Boot 4.1.1 backend for "FREYJA AI DIGITAL STUDIO". Base package `com.astra.freyja`.

## Build & test
- Requires **JDK 26** (`pom.xml` `<java.version>26</java.version>`). The machine's default `java`/`mvn` may be Java 8 + Maven 3.6.3 and will NOT build this project. Use the Maven wrapper which pins Maven 3.9.16:
  - `.\mvnw.cmd test` (Windows) / `./mvnw test`
  - single test: `.\mvnw.cmd test -Dtest=FreyjaApplicationTests`
  - If builds fail with "invalid target release: 26" (or "class file has wrong version 61.0, should be 52.0"), the shell is on the wrong JDK. **This machine's JDK 26 is at `F:\program_file\jdk26`** — set it before running:
    - PowerShell: `$env:JAVA_HOME="F:\program_file\jdk26"`
    - then `.\mvnw.cmd compile` (Windows) / `./mvnw compile`
- No lint/format/typecheck tooling is configured; `mvn test`/`mvn compile` are the only verification commands.

## Tech stack facts
- **DAO layer is MyBatis-Plus** via `com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17`. This is the Spring Boot 4 variant — do NOT "fix" it to `mybatis-plus-spring-boot3-starter` or `mybatis-plus-boot-starter`; they are incompatible with Spring Boot 4.x.
- Lombok is wired through explicit `maven-compiler-plugin` `annotationProcessorPaths` executions (`default-compile`, `default-testCompile`) in `pom.xml`. Preserve this if editing compiler config.
- Spring Data Redis + Spring Web MVC (Spring Boot 4 renamed starters: `spring-boot-starter-webmvc`, `spring-boot-starter-data-redis`).
- MySQL `mysql-connector-j` (runtime). `application.yaml` has only the app name — no datasource/Redis config yet.

## External services
- Connection info for MySQL / Redis / MinIO lives in `docs/components.md` (host, port, credentials, notes). Consult it before wiring datasource/Redis/MinIO config; the passwords there are local dev credentials.

## Codebase state
- Implemented so far:
  - **Dict module**: `entity/BaseEntity`, `SysDictType`, `SysDictData`; `dao/*Mapper`; `service/DictService`(+impl) + `DictTypeService`(+impl); `controller/DictController` + `DictTypeController`; `dto/` (DictType/DictData Query/DTO); `config/` (MybatisPlus, Redis, MyMetaObjectHandler); `common/` (R, BizException, GlobalExceptionHandler). API spec in `docs/dict-api.md`.
  - **AI Provider & Model module**: `entity/AiProvider`, `AiModel`; `dao/AiProviderMapper`, `AiModelMapper`; `service/AiProviderService`(+impl), `AiModelService`(+impl), `AiModelFactory`(+impl), `CircuitBreakerChatModel`; `controller/AiProviderController`, `AiModelController`; `dto/AiProviderDTO`, `AiProviderVO`, `AiProviderQuery`, `AiModelDTO`, `AiModelQuery`; `util/CryptoUtil`. API spec in `docs/ai-provider-api.md`. Unit tests in `src/test/java/com/astra/freyja/service/` and `util/`.
  - **FastAPI 生图与 MinIO 归档调度模块 (原 ComfyUI 直连解耦并全面转向 FastAPI / OpenAI 规范)**: 提供商接入类型纯化为 OPENAI 与 OLLAMA，FastAPI 网关作为 OpenAI 兼容生图提供商，Java 后端通过 `AiImageApiService` 统一调度生图与 MinIO 归档，彻底移除原生 ComfyUI WebSocket/8188 紧耦合与专用引擎。通用异步线程池在 `config/RenderAsyncConfig` 中配置。
  - **Resource Library module (人物身份层与多造型层统一架构 ResCharacter + ResCharacterLook × N、默认造型互斥流转与单一设定图决策引擎、图文一致性状态管理、场景管理、核心道具资产管理与 Prompt 组装引擎)**: `entity/ResCharacter`, `ResCharacterLook`, `ResCharacterOutfit` (兼容门面), `ResScene`, `ResProp`; `dao/ResCharacterMapper`, `ResCharacterLookMapper`, `ResCharacterOutfitMapper`, `ResSceneMapper`, `ResPropMapper`; `service/ResCharacterService`(+impl), `ResCharacterLookService`(+impl), `ResCharacterOutfitService`(+impl), `CharacterVisualAssetResolver`(+impl), `ResSceneService`(+impl), `ResPropService`(+impl), `PromptAssembleService`(+impl), `MediaAssetService`(+impl); `controller/ResCharacterController`, `ResCharacterLookController`, `ResCharacterOutfitController`, `ResSceneController`, `ResPropController`, `PromptAssembleController`, `MediaAssetController`; `dto/res/*` (including ResCharacterLookDTO/VO, ResCharacterOutfitDTO/VO, CharacterVisualPromptDeriveDTO/VO, OutfitPromptDeriveDTO/VO, ResPropDTO, ResPropVO, ResPropQuery, ResPropOptionVO). 人物设定图全面归属于造型表，前端 `CharacterDrawer.vue` 采用五并列标签页（基础资料、稳定身份特征、人物设定参考图、服装与造型、其他设置）集中维护。API spec in `docs/resource-library-api.md`.
  - **Drama-Episode-Scene-Shot module (短剧、剧集、情景与分镜四层大纲工作台)**: `entity/Drama`, `DramaEpisode`, `DramaScene`, `DramaShot`; `dao/DramaMapper`, `DramaEpisodeMapper`, `DramaSceneMapper`, `DramaShotMapper`; `service/DramaService`(+impl), `DramaEpisodeService`(+impl), `DramaSceneService`(+impl), `DramaShotService`(+impl); `controller/DramaController`, `DramaEpisodeController`, `DramaSceneController`, `DramaShotController`; `dto/drama/*`. API spec in `docs/drama-api.md`. Unit tests in `src/test/java/com/astra/freyja/service/`.
  - **Parallel Segmented Script Decompose module (整章小说一键批量分镜生成与并行分段解析架构: Planner AI 单次剧情分段 + Worker AI × N 并行并发调度聚焦生成镜头剧本 scriptContent 与视听结构，Worker 不再生成首帧图 Prompt + Java 确定性合并重排与防碎镜碎片率统计 ShotMergeService + 后续专属 AI 结合原文与镜头剧本精准生成双轨 Prompt + 级联持久化)**: `dto/script/*` (StorySegment, PlannerDecomposeResultVO, WorkerSegmentContext, WorkerShotResult, SegmentShotResult, FragmentationStatsVO, ChapterParseTaskVO); `service/ChapterDecompositionService`(+impl), `ParallelShotGenerationService`(+impl), `ShotMergeService`(+impl); `service/ScriptDecomposeService`(+impl); `controller/ScriptDecomposeController`. API spec in `docs/script-decompose-api.md`. Unit/Integration tests in `src/test/java/com/astra/freyja/service/ChapterParallelDecomposeTest.java`, `ScriptPipelineIntegrationTest.java`, `ScriptDecomposeServiceTest.java`.
  - **System Config module (系统参数配置与 Spring AI 系统提示词等核心常量动态抽离)**: `entity/SysConfig`; `dao/SysConfigMapper`; `service/SysConfigService`(+impl); `controller/SysConfigController`; `dto/config/*`. Unit tests in `src/test/java/com/astra/freyja/service/SysConfigServiceTest.java`.
  - **Character Resolution & Entity Registry module (跨集角色消歧、实体注册表、五级消歧流水线与角色合并)**: `entity/ResCharacterAlias`, `ResCharacterEvidence`, `ResCharacterResolution`, `entity/enums/*`; `dao/ResCharacterAliasMapper`, `ResCharacterEvidenceMapper`, `ResCharacterResolutionMapper`; `service/CharacterRegistryService`(+impl), `CharacterResolutionService`(+impl), `CharacterResolutionAiService`(+impl); `dto/res/*` (Context, Result, Mention, Evidence, AI Request/Response, Merge); unit tests in `src/test/java/com/astra/freyja/service/CharacterEntityResolutionTest.java`.
  - **Shot Group module (连续镜头组/叙事容器与串行批渲染调度)**: `entity/DramaShotGroup`, `entity/DramaShot`; `dao/DramaShotGroupMapper`; `service/DramaShotGroupService`(+impl); `controller/DramaShotGroupController`; `dto/drama/*` (ShotGroup CRUD, Split, Merge, Reorder); `dto/script/DecomposedShotGroupVO`. Unit tests in `src/test/java/com/astra/freyja/service/DramaShotGroupServiceTest.java`.
  - **AI Skills 知识资产管理模块**: `entity/AiSkill`, `AiSkillVersion`, `AiSkillFile`; `dao/AiSkillMapper`, `AiSkillVersionMapper`, `AiSkillFileMapper`; `skill/model/SkillManifest`, `SkillManifestReference`, `SkillPackageExtractResult`; `skill/service/SkillPackageValidator`, `SkillRepository`(+impl), `SkillAdminService`(+impl), `SkillCatalogService`(+impl), `SkillContentService`(+impl), `SkillBuiltinInitializer`; `skill/tool/LoadSkillToolFactory`, `LoadSkillToolSession`; `controller/AiSkillController` (`/system/skills/**`). 支持 ZIP 安全防暴解压、版本发布与回滚、MinIO 目录存储与按需 Tool Calling.
  - **Cinematography 视听导演规划与 MiniMax H3 双轨翻译层 (DirectorPlan)**: `director/model/DirectorPlan`, `CameraBeat`, `AppliedSkillRef`; `director/service/DirectorPlanValidator`, `DirectorPlanMergeService`(+impl), `DirectorPlanningService`(+impl); `ShotAiVisualPlanService` 接入导演规划与 H3 三段式/六段式翻译。纯物理校验保护 AUTO 状态，严禁关键字正则猜谜.
  - **Shot Video Take History module (分镜视频抽卡历史与历史版本重选)**: `entity/DramaShotVideoTake`, `dao/DramaShotVideoTakeMapper`, `service/ShotVideoTakeService`(+impl), `dto/drama/ShotVideoTakeQuery`, `ShotVideoTakeVO`, `ShotVideoTakeSelectVO`, `controller/DramaShotController` (`GET/POST /drama/shot/{shotId}/video-takes/**`); 前端独立抽屉 `ShotVideoHistoryDrawer.vue` 集成于 `ShotRenderStepModal.vue`。支持分镜抽卡版本追溯、并发乱序时间线保护、一键重选与尾帧缓存联动失效。
  - **Video Processing & Shot Source Selection module (视频后处理分镜与抽卡源选择、不可变 URL 快照与模型中心动态驱动)**: `entity/MediaProcessTask`, `entity/enums/VideoProcessSourceType`; `dto/video/*` (ResolvedVideoProcessSource, VideoProcessingModelParams, VideoProcessingModelOptionVO, ShotVideoSourceOptionVO, ShotVideoSourceQuery, VideoProcessProbeSourceDTO, ResolvedVideoProcessingModel, VideoProcessSubmitDTO, VideoProcessResultVO); `service/VideoProcessSourceResolver`(+impl), `VideoProcessingModelResolver`(+impl), `VideoProcessingService`(+impl); `controller/VideoProcessingController` (`GET /source-shots`, `POST /probe-source`, `GET /models`); 前端分镜选择器弹窗 `ShotVideoPickerDialog.vue`，重构超分增强 `VideoUpscale.vue` 与平滑补帧 `FrameInterpolation.vue` 工作台，完全移除硬编码模型与参数。API spec in `docs/video-processing-api.md`. Unit tests in `src/test/java/com/astra/freyja/service/VideoProcessSourceResolverTest.java`, `VideoProcessingModelResolverTest.java`, `VideoProcessingServiceTest.java`.
  - **Docker Containerization & Open-Source Orchestration (核心业务容器化 + 外部 ComfyUI/GPU 算力节点解耦编排)**: 根目录 `docker-compose.yml`, `.env.example`, 后端 `Dockerfile` (Temurin JDK 26 + 内置 FFmpeg), 前端 `front/Dockerfile` (Node 22 + Nginx SPA 反向代理), FastAPI 网关 `comfy_gateway/Dockerfile`, 以及 MySQL 8.0 自动初始化脚本 `docker/mysql/init/01_init.sql`。支持跨平台（Windows / Linux）一键部署，并通过 `host.docker.internal` 直连本地 Windows 秋叶整合包或远端 Linux GPU 服务器。部署规范在 `docs/docker-deployment.md`.
  - **Frontend**: Full views for 通用数据字典 (`/system/dict`), AI 提供商与模型管理 (`/system/ai-provider`), 系统参数配置 (`/system/config`), AI Skills 知识资产管理 (`/system/skills`，支持 ZIP 上传导入、内置视听技能、版本切换/回滚/停用、文件树预览与在线调试), 角色、场景与道具资产管理 (`/assets`，按「👥 角色资产管理 -> 🏞️ 场景资产管理 -> 🗡️ 道具资产管理」顺序布局，支持网格/表格视图及设计图上传，包含「🎙️ AI 角色专属音色设计工坊」弹窗 `VoiceDesignModal.vue` 自由设计母音并自主选择提供商与 TTS 语音模型), 剧作大纲与可视化分镜卡片流工作台 (`/drama`)，包含「⚡ AI 一键剧本拆解」全功能弹窗 (`ScriptDecomposeModal.vue`)、分镜抽屉 `ShotDrawer.vue`（支持景别/运镜 AUTO 选择、DirectorPlan 结构化卡片与台词声音克隆）、双轨提示词智能衍生弹窗 `ShotPromptDeriveModal.vue`（展示 DirectorPlan 时间轴及 Camera Beats），两阶段渲染工坊 `ShotRenderStepModal.vue`（集成视频抽卡历史与版本重选抽屉），视频超分 (`/production/upscale`) 与补帧 (`/production/interpolation`) 工作台（支持分镜及历史 Take 来源直选、动态模型调度与不可变快照追溯）。
- `front/` is initialized with **Vue 3 + Vite + TypeScript + Pinia + Element Plus + Tailwind CSS**.
  - Node 24.9.0 runtime is located at `D:\program_file\mvn\nvm\v24.9.0`.
  - Frontend build: `$env:PATH = "D:\program_file\mvn\nvm\v24.9.0;$env:PATH"; cd front; npm run build`
  - Frontend dev: `$env:PATH = "D:\program_file\mvn\nvm\v24.9.0;$env:PATH"; cd front; npm run dev`
- `HELP.md` is gitignored, generated by Spring Initializr — do not edit.

## Engineering rules & design principles
- **严禁在前端对 19 位雪花 ID（Snowflake ID）执行 `Number()` 转换（Snowflake ID Precision Loss 铁律）**：
  - **根因事实**：后端所有业务实体主键（`BaseEntity.id`）均基于 64 位雪花算法生成（19 位长整型，如 `2032095628944588801`）。JavaScript Number 基于 IEEE 754 双精度浮点数，最大安全整数 `Number.MAX_SAFE_INTEGER` 仅为 $2^{53}-1$（16 位）。对 19 位雪花 ID 调用 `Number(id)`、`+id` 或 `parseInt(id)` 会直接导致低位精度丢失被截断抹零，后端据此篡改后的 ID 查询必报 `404: 指定的实体/提供商不存在`；
  - **架构基建**：后端 `JacksonConfig` 已全局注册 `ToStringSerializer`（`Long` 序列化为字符串），同时配置了 `LongDeserializer`（自动兼容数字与字符串形式解析为 `Long`）；
  - **前端守则**：
    1. 前端所有实体 ID、外键关联 ID（如 `characterId`、`providerId`、`shotId`、`sceneId`）必须保持为原始字符串 `string`（或 `string | number`），**严禁调用 `Number(id)` 转换**；
    2. 下拉组件（`el-select`/`el-option`）的 `:key` 与 `:value` 必须统一绑定为 `String(p.id)`；
    3. 请求载荷中空值应清洗为 `undefined`，严禁传空字符串 `""`、`0` 或 `NaN`。
- **严禁在后端编写脆弱的关键字/正则字符串猜谜逻辑（Keyword Heuristics / String Guessing）**：
  - 严禁出现如 `if (action.contains("坐") || action.contains("刀") || action.contains("沙发"))` 这种脆弱死板的硬编码推断代码。
  - **职责归位原则**：
    - **语义理解与结构化提取**：必须由 **AI 大模型（Planner/Worker）在拆解源头直接输出结构化 JSON**，或由创作者在工作台显式指定；
    - **Java 服务端职责**：专注于**数据结构透传、Character Blackboard 角色状态看板分发、状态去重清洗、冲突校验与持久化**，绝不越俎代庖充当二流自然语言解析器；
    - **无值即为 null**：若无显式声明的状态，直接保持 `null` 或继承透传，严禁由 Java 本地代码擅自揣测、硬编码或凭空脑补。

## Docs conventions
- `Readme.md` is the Chinese design/task spec. Its section 1.2 tech-stack table distinguishes actually-introduced deps from "规划中" (planned-only) items — keep the "实际引入" rows aligned with `pom.xml` when adding/removing dependencies.
