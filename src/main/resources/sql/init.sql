-- ==============================================================================
-- Freyja AI 短剧分镜管理与调度系统 - 全量初始化数据库脚本 (init.sql)
-- 字符集: utf8mb4 / utf8mb4_unicode_ci
-- 适用数据库: MySQL 8.0+
-- ==============================================================================

CREATE DATABASE IF NOT EXISTS `freyja` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `freyja`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ==============================================================================
-- 1. 系统通用字典与配置模块 (System Dict & Config)
-- ==============================================================================

-- 1.1 字典类型表
DROP TABLE IF EXISTS `sys_dict_type`;
CREATE TABLE `sys_dict_type` (
    `id`          BIGINT UNSIGNED NOT NULL COMMENT '主键',
    `dict_type`   VARCHAR(64)  NOT NULL COMMENT '字典类型编码',
    `dict_name`   VARCHAR(100) NOT NULL COMMENT '字典类型名称',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_dict_type` (`dict_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '字典类型表';

-- 1.2 字典数据项表
DROP TABLE IF EXISTS `sys_dict_data`;
CREATE TABLE `sys_dict_data` (
    `id`          BIGINT UNSIGNED NOT NULL COMMENT '主键',
    `dict_type`   VARCHAR(64)  NOT NULL COMMENT '归属字典类型编码',
    `dict_label`  VARCHAR(100) NOT NULL COMMENT '字典标签（展示用）',
    `dict_value`  VARCHAR(100) NOT NULL COMMENT '字典键值（存储用）',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '显示排序',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_dict_type` (`dict_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '字典数据项表';

-- 1.3 系统参数配置表 (sys_config)
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config` (
    `id`           BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `config_name`  VARCHAR(100)    NOT NULL COMMENT '配置名称',
    `config_key`   VARCHAR(100)    NOT NULL COMMENT '配置键名 (唯一)',
    `config_value` LONGTEXT        NOT NULL COMMENT '配置键值',
    `config_type`  VARCHAR(32)     NOT NULL DEFAULT 'STRING' COMMENT '配置类型 (STRING/TEXT/JSON/NUMBER/BOOLEAN)',
    `is_builtin`   TINYINT         NOT NULL DEFAULT 0 COMMENT '是否系统内置 0-否 1-是 (内置禁止删除)',
    `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`    BIGINT          DEFAULT 0 COMMENT '创建人 ID',
    `create_time`  DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    BIGINT          DEFAULT 0 COMMENT '更新人 ID',
    `update_time`  DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`       VARCHAR(500)    DEFAULT NULL COMMENT '备注说明',
    PRIMARY KEY (`id`),
    KEY `idx_config_key` (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '系统参数配置表';

-- ==============================================================================
-- 2. AI 模型提供商与流水线任务记录 (AI Provider & Task Machine)
-- ==============================================================================

-- 2.1 AI 提供商表
DROP TABLE IF EXISTS `ai_provider`;
CREATE TABLE `ai_provider` (
    `id`                BIGINT UNSIGNED NOT NULL COMMENT '主键',
    `provider_code`     VARCHAR(64)   NOT NULL COMMENT '提供商编码，未删除记录中唯一（如 deepseek/openai/ollama）',
    `provider_name`     VARCHAR(100)  NOT NULL COMMENT '提供商名称',
    `provider_type`     VARCHAR(32)   NOT NULL COMMENT '接入类型 OPENAI-OpenAI规范兼容 OLLAMA',
    `api_key`           VARCHAR(1024) DEFAULT NULL COMMENT 'API Key（AES 加密密文，接口返回掩码）',
    `base_url`          VARCHAR(512)  DEFAULT NULL COMMENT 'Base URL',
    `timeout`           INT           NOT NULL DEFAULT 30 COMMENT '请求超时（秒）',
    `max_retries`       INT           NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `enable_breaker`    TINYINT       NOT NULL DEFAULT 1 COMMENT '是否熔断 0-关 1-开',
    `breaker_threshold` INT           NOT NULL DEFAULT 10 COMMENT '熔断阈值（连续失败次数）',
    `breaker_timeout`   INT           NOT NULL DEFAULT 30 COMMENT '熔断恢复时间（秒）',
    `status`            TINYINT       NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`         BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`            VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_provider_code_deleted` (`provider_code`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 提供商配置表';

-- 2.2 AI 模型表
DROP TABLE IF EXISTS `ai_model`;
CREATE TABLE `ai_model` (
    `id`          BIGINT UNSIGNED NOT NULL COMMENT '主键',
    `provider_id` BIGINT UNSIGNED NOT NULL COMMENT '归属提供商 ID',
    `model_code`  VARCHAR(64)  NOT NULL COMMENT '模型标识，同提供商的未删除记录中唯一',
    `model_name`  VARCHAR(100) NOT NULL COMMENT '模型名称（展示用）',
    `model_type`  VARCHAR(32)  NOT NULL DEFAULT 'CHAT' COMMENT '模型类型: CHAT/TXT2IMG/IMG2IMG/TXT_IMG2IMG/TXT2VIDEO_FIRST_LAST/TXT2VIDEO_REF/TTS/LIP_SYNC/EMBEDDING/VIDEO_UPSCALE/FRAME_INTERPOLATION',
    `temperature` DOUBLE       DEFAULT NULL COMMENT '采样温度',
    `max_tokens`  INT          DEFAULT NULL COMMENT '最大输出 Token 数',
    `top_p`       DOUBLE       DEFAULT NULL COMMENT '核采样概率',
    `params_json` TEXT         DEFAULT NULL COMMENT '额外模型参数 JSON（frequency_penalty 等）',
    `max_images`  INT          DEFAULT NULL COMMENT '最大参考图限制 (张, 图生图/图生视频模型必填)',
    `max_audios`  INT          DEFAULT NULL COMMENT '最大参考音频限制 (段, 图生视频模型扩展)',
    `max_videos`  INT          DEFAULT NULL COMMENT '最大参考视频限制 (个, 图生视频模型扩展)',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '显示排序',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_provider_model_deleted` (`provider_id`, `model_code`, `deleted`),
    KEY `idx_provider_id` (`provider_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 模型配置表';

-- 2.3 AI 任务流水线记录表 (ai_task)
DROP TABLE IF EXISTS `ai_task`;
CREATE TABLE `ai_task` (
    `id`              BIGINT UNSIGNED NOT NULL COMMENT '主键ID (雪花算法)',
    `drama_id`        BIGINT UNSIGNED DEFAULT 0 COMMENT '归属短剧ID，0为未绑定独立任务',
    `episode_id`      BIGINT UNSIGNED DEFAULT NULL COMMENT '归属剧集ID (可选)',
    `task_type`       VARCHAR(64)     NOT NULL COMMENT '任务类型: SCRIPT_CHUNK, PLOT_EXTRACTION, SHOT_DECOMPOSE, SHOT_ENRICHMENT, CONTINUITY_CHECK, ENTITY_RESOLUTION, CHAPTER_DECOMPOSE, SHOT_PROMPT_DERIVE',
    `status`          VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT '任务状态: PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED/RETRYING/CANCELLED',
    `target_id`       VARCHAR(64)     DEFAULT NULL COMMENT '关联业务目标ID (如 chunkId, plotId, shotGroupId, shotId)',
    `parent_task_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '父任务 ID',
    `input_payload`   LONGTEXT        DEFAULT NULL COMMENT '输入数据载荷 (JSON/文本片段)',
    `output_payload`  LONGTEXT        DEFAULT NULL COMMENT '输出数据载荷 (结构化 JSON)',
    `model_code`      VARCHAR(64)     DEFAULT NULL COMMENT 'AI 模型代码',
    `max_tokens`      INT             DEFAULT NULL COMMENT '最大 Token 限制',
    `consumed_tokens` INT             DEFAULT NULL COMMENT '消耗 Token 统计',
    `retry_count`     INT             NOT NULL DEFAULT 0 COMMENT '重试次数',
    `error_message`   TEXT            DEFAULT NULL COMMENT '异常错误信息',
    `started_at`      DATETIME        DEFAULT NULL COMMENT '任务开始执行时间',
    `finished_at`     DATETIME        DEFAULT NULL COMMENT '任务结束时间',
    `create_by`       BIGINT          DEFAULT 0 COMMENT '创建人 ID',
    `create_time`     DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT          DEFAULT 0 COMMENT '更新人 ID',
    `update_time`     DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`          VARCHAR(500)    DEFAULT NULL COMMENT '备注说明',
    PRIMARY KEY (`id`),
    KEY `idx_drama_task` (`drama_id`, `task_type`),
    KEY `idx_drama_episode` (`drama_id`, `episode_id`),
    KEY `idx_parent_task` (`parent_task_id`),
    KEY `idx_status` (`status`),
    KEY `idx_target_id` (`target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 流水线任务记录表';

-- 2.4 AI 技能定义表 (ai_skill)
DROP TABLE IF EXISTS `ai_skill`;
CREATE TABLE `ai_skill` (
    `id`                 BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `name`               VARCHAR(64)     NOT NULL COMMENT '唯一稳定标识，小写 slug，如 cinematography',
    `display_name`       VARCHAR(100)    NOT NULL COMMENT '页面展示名称',
    `description`        VARCHAR(1024)   DEFAULT NULL COMMENT '标准 SKILL.md frontmatter description',
    `enabled`            TINYINT         NOT NULL DEFAULT 1 COMMENT '全局开关: 0-停用 1-启用',
    `current_version_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '当前发布的有效版本 ID',
    `sort_order`         INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `create_by`          BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`        DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`          BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`        DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`             VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_enabled` (`enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 技能定义表';

-- 2.5 AI 技能版本表 (ai_skill_version)
DROP TABLE IF EXISTS `ai_skill_version`;
CREATE TABLE `ai_skill_version` (
    `id`             BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `skill_id`       BIGINT UNSIGNED NOT NULL COMMENT '归属逻辑 Skill ID',
    `revision_no`    INT             NOT NULL COMMENT '服务端递增版本号，从 1 开始',
    `content`        LONGTEXT        NOT NULL COMMENT '标准 SKILL.md 原始正文',
    `content_hash`   VARCHAR(128)    NOT NULL COMMENT '标准 Skill 包 SHA-256 哈希值',
    `content_size`   BIGINT          NOT NULL DEFAULT 0 COMMENT '标准 Skill 包解压后总字节数',
    `create_by`      BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`    DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`      BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`    DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`         VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_revision` (`skill_id`, `revision_no`),
    KEY `idx_skill_hash` (`skill_id`, `content_hash`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 技能版本表';

-- 2.6 AI 技能标准包文件索引表 (ai_skill_file)
DROP TABLE IF EXISTS `ai_skill_file`;
CREATE TABLE `ai_skill_file` (
    `id`                BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `skill_version_id`  BIGINT UNSIGNED NOT NULL COMMENT '归属 Skill 版本 ID',
    `relative_path`     VARCHAR(512)    NOT NULL COMMENT '相对 Skill 根目录的安全路径',
    `file_type`         VARCHAR(32)     NOT NULL COMMENT 'ENTRYPOINT/SCRIPT/REFERENCE/ASSET/RESOURCE',
    `object_key`        VARCHAR(512)    NOT NULL COMMENT 'MinIO 对象键',
    `content_hash`      VARCHAR(128)    NOT NULL COMMENT '文件 SHA-256 哈希值',
    `content_size`      BIGINT         NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `create_by`         BIGINT         DEFAULT NULL COMMENT '创建人 ID',
    `create_time`       DATETIME       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT         DEFAULT NULL COMMENT '更新人 ID',
    `update_time`       DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`            VARCHAR(500)   DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_version_path` (`skill_version_id`, `relative_path`),
    KEY `idx_skill_version` (`skill_version_id`),
    KEY `idx_file_type` (`file_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 技能标准包文件索引表';

-- ==============================================================================
-- 3. 资源库模块：人物、多造型、场景、消歧与别名映射
-- ==============================================================================

-- 3.1 人物角色资产表
DROP TABLE IF EXISTS `res_character`;
CREATE TABLE `res_character` (
    `id`                BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归属短剧ID，0为公共资源库',
    `name`              VARCHAR(100)    NOT NULL COMMENT '人物名称/角色名 (初次提及或正式名)',
    `canonical_name`    VARCHAR(100)    DEFAULT NULL COMMENT '正式规范姓名 (如 林婉清)',
    `display_name`        VARCHAR(100)    DEFAULT NULL COMMENT '展示称谓/初次提及称谓 (如 年轻女人)',
    `reference_image_url` VARCHAR(512)    DEFAULT NULL COMMENT '人物身份设定参考图URL (MinIO)',
    `gender`              VARCHAR(16)     NOT NULL DEFAULT 'UNKNOWN' COMMENT '性别 MALE/FEMALE/OTHER/UNKNOWN',
    `age_group`           VARCHAR(32)     DEFAULT NULL COMMENT '年龄段 (如 TEENAGER/YOUTH/MIDDLE_AGED/ELDERLY)',
    `role_type`           VARCHAR(32)     NOT NULL DEFAULT 'PROTAGONIST' COMMENT '角色定位 PROTAGONIST(主角)/ANTAGONIST(反派)/SUPPORTING(配角)/EXTRA(路人)',
    `identity_status`     VARCHAR(32)     NOT NULL DEFAULT 'CONFIRMED' COMMENT '身份状态 UNKNOWN/PARTIAL/CONFIRMED/UNRESOLVED/MERGED',
    `merged_to_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '若已合并，指向目标主角色ID',
    `personality`         VARCHAR(255)    DEFAULT NULL COMMENT '性格/人设简述 (内在心智、处事原则与对白基调)',
    `appearance_desc`     TEXT            DEFAULT NULL COMMENT '中文外貌视觉特征描述 (原著外貌真实源 SSOT)',
    `appearance_prompt`   TEXT            DEFAULT NULL COMMENT '基础外貌特征Prompt (英文, 如 1girl, 20yo, long black hair...)',
    `negative_prompt`     TEXT            DEFAULT NULL COMMENT '角色专属全局负向Prompt',
    `trigger_words`       VARCHAR(255)    DEFAULT NULL COMMENT '触发词/激活词 (如 sarah_connor, realistic)',
    `lora_name`           VARCHAR(128)    DEFAULT NULL COMMENT '绑定的角色LoRA文件名',
    `lora_weight`         DECIMAL(4,2)    DEFAULT 1.00 COMMENT 'LoRA权重',
    `voice_id`          VARCHAR(64)     DEFAULT NULL COMMENT 'TTS配音音色ID',
    `voice_desc`        VARCHAR(512)    DEFAULT NULL COMMENT '音色设计提示词 (自然语言描述)',
    `voice_sample_text` VARCHAR(255)    DEFAULT NULL COMMENT '试音参考台词文本',
    `voice_sample_url`  VARCHAR(512)    DEFAULT NULL COMMENT '角色基准参考样音 (MinIO URL)',
    `first_appearance`  VARCHAR(128)    DEFAULT NULL COMMENT '初次出场集数/场景',
    `last_appearance`   VARCHAR(128)    DEFAULT NULL COMMENT '最近出场集数/场景',
    `sort_order`        INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `status`            TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`         BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`            VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_drama_id` (`drama_id`),
    KEY `idx_role_type` (`role_type`),
    KEY `idx_identity_status` (`identity_status`),
    KEY `idx_merged_to_id` (`merged_to_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '人物角色资产表';

-- 3.2 角色别名与提及映射表
DROP TABLE IF EXISTS `res_character_alias`;
CREATE TABLE `res_character_alias` (
    `id`                BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归属短剧ID',
    `character_id`      BIGINT UNSIGNED NOT NULL COMMENT '归属角色ID',
    `alias`             VARCHAR(100)    NOT NULL COMMENT '别名/提及称谓 (如 女人, 她, 林小姐, 林总)',
    `alias_type`        VARCHAR(32)     NOT NULL DEFAULT 'OTHER' COMMENT '别名类型: NAME(正式/化名), PRONOUN(代词), DESCRIPTION(外貌描述), TITLE(头衔尊称), NICKNAME(昵称), ROLE(职业身份), OTHER',
    `source_episode_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '首次识别到的剧集ID',
    `confidence`        DECIMAL(4,2)    NOT NULL DEFAULT 1.00 COMMENT '置信度',
    `status`            TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`         BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`            VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_character_id` (`character_id`),
    KEY `idx_drama_alias` (`drama_id`, `alias`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色别名与提及映射表';

-- 3.3 角色身份依据与证据表
DROP TABLE IF EXISTS `res_character_evidence`;
CREATE TABLE `res_character_evidence` (
    `id`                BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归属短剧ID',
    `character_id`      BIGINT UNSIGNED NOT NULL COMMENT '归属角色ID',
    `episode_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '证据所在剧集ID',
    `scene_id`          BIGINT UNSIGNED DEFAULT NULL COMMENT '证据所在场次ID',
    `source_text`       TEXT            NOT NULL COMMENT '剧本证据原文 (如 “我叫林婉清”)',
    `evidence_type`     VARCHAR(64)     NOT NULL DEFAULT 'OTHER' COMMENT '证据类型: FIRST_APPEARANCE, SELF_INTRODUCTION, EXPLICIT_NAME, EXPLICIT_REFERENCE, RELATIONSHIP, APPEARANCE_MATCH, CONTEXT_MATCH, SCENE_CONTINUITY, OTHER',
    `confidence`        DECIMAL(4,2)    NOT NULL DEFAULT 1.00 COMMENT '依据置信度',
    `reason`            VARCHAR(500)    DEFAULT NULL COMMENT '推理判断说明',
    `status`            TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 1-有效 0-无效',
    `create_by`         BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`            VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_character_id` (`character_id`),
    KEY `idx_episode_id` (`episode_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色身份依据与证据表';

-- 3.4 角色消歧未决项与人工决议表
DROP TABLE IF EXISTS `res_character_resolution`;
CREATE TABLE `res_character_resolution` (
    `id`                      BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`                BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归属短剧ID',
    `episode_id`              BIGINT UNSIGNED DEFAULT NULL COMMENT '发生剧集ID',
    `scene_id`                BIGINT UNSIGNED DEFAULT NULL COMMENT '发生场次ID',
    `source_mention`          VARCHAR(100)    NOT NULL COMMENT '待消歧提及称谓 (如 那个女人)',
    `source_text`             TEXT            NOT NULL COMMENT '上下文剧本段落',
    `candidate_character_ids` VARCHAR(512)    NOT NULL COMMENT '候选角色ID列表 JSON (如 [101, 103])',
    `resolution_status`       VARCHAR(32)     NOT NULL DEFAULT 'UNRESOLVED' COMMENT '消歧状态: UNRESOLVED(未决待确认), RESOLVED(已人工决议), IGNORED(独立新角色)',
    `resolved_character_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '最终决议绑定的角色ID',
    `confidence`              DECIMAL(4,2)    DEFAULT NULL COMMENT 'AI 评估置信度',
    `reason`                  VARCHAR(500)    DEFAULT NULL COMMENT 'AI 歧义分析理由',
    `create_by`               BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`             DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`               BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`             DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`                 TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`                  VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_drama_id` (`drama_id`),
    KEY `idx_res_status` (`resolution_status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色消歧未决项与人工决议表';

-- 3.5 人物造型变体表
DROP TABLE IF EXISTS `res_character_look`;
CREATE TABLE `res_character_look` (
    `id`                  BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `character_id`        BIGINT UNSIGNED NOT NULL COMMENT '归属人物ID',
    `look_name`           VARCHAR(100)    NOT NULL COMMENT '造型名称 (如: 日常便服 / 晚礼服 / 战术装)',
    `is_default`          TINYINT         NOT NULL DEFAULT 0 COMMENT '是否默认造型 0-否 1-是',
    `reference_image_url` VARCHAR(512)    DEFAULT NULL COMMENT '造型参考图URL (MinIO)',
    `image_status`        VARCHAR(32)     NOT NULL DEFAULT 'MISSING' COMMENT '图片状态: MISSING/SYNCED/STALE',
    `visual_version`      INT             NOT NULL DEFAULT 1 COMMENT '造型Prompt版本',
    `image_visual_version` INT            NOT NULL DEFAULT 0 COMMENT '图片对应的Prompt版本',
    `outfit_prompt`       TEXT            DEFAULT NULL COMMENT '服饰装扮Prompt (英文, 如 wearing dark trench coat, leather gloves...)',
    `look_type`           VARCHAR(32)     NOT NULL DEFAULT 'COSTUME' COMMENT '视觉状态类型 BASE/COSTUME/AGE_PHASE/DISGUISE/DAMAGE/CUSTOM',
    `design_desc`         TEXT            DEFAULT NULL COMMENT '造型视觉概念描述 (颜色/面料/轮廓/配饰/妆发)',
    `appearance_prompt`   TEXT            DEFAULT NULL COMMENT '妆发、年龄阶段、伤痕等非服装视觉描述',
    `negative_prompt`     TEXT            DEFAULT NULL COMMENT '造型专属负向约束',
    `lora_name`           VARCHAR(128)    DEFAULT NULL COMMENT '服装专属LoRA (可选)',
    `lora_weight`         DECIMAL(4,2)    DEFAULT 1.00 COMMENT 'LoRA权重',
    `sort_order`          INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `status`              TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`           BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`         DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`           BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`         DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`              VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_character_id` (`character_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '人物造型表';

-- 3.6 场景环境资产表
DROP TABLE IF EXISTS `res_scene`;
CREATE TABLE `res_scene` (
    `id`                  BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`            BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归属短剧ID，0为公共资源库',
    `name`                VARCHAR(100)    NOT NULL COMMENT '场景名称 (如: 顶层总裁办公室 / 暴雨夜市街道)',
    `description`         TEXT            DEFAULT NULL COMMENT '场景中文背景与视觉细节描述 (原著视觉源)',
    `cover_url`           VARCHAR(512)    DEFAULT NULL COMMENT '场景封面/参考图URL',
    `scene_type`          VARCHAR(32)     DEFAULT NULL COMMENT '空间类型 INDOOR(室内)/OUTDOOR(室外)/STUDIO(影棚)/VIRTUAL(虚构)，未指定时为空',
    `time_of_day`         VARCHAR(32)     NOT NULL DEFAULT 'DAY' COMMENT '时间时段 DAY(日间)/NIGHT(夜间)/SUNSET(黄昏)/DAWN(拂晓)',
    `weather_atmosphere`  VARCHAR(64)     DEFAULT NULL COMMENT '天气氛围 (如 SUNNY/RAINY/FOGGY/CYBERPUNK/NEON/MOODY)',
    `scene_prompt`        TEXT            DEFAULT NULL COMMENT '场景生图Prompt (英文, 包含空间、陈设与光影色温一体化描述)',
    `negative_prompt`     TEXT            DEFAULT NULL COMMENT '场景专属负向Prompt',
    `lora_name`           VARCHAR(128)    DEFAULT NULL COMMENT '场景风格LoRA',
    `lora_weight`         DECIMAL(4,2)    DEFAULT 1.00 COMMENT 'LoRA权重',
    `reference_image_url` VARCHAR(512)    DEFAULT NULL COMMENT 'ControlNet/IP-Adapter 空间参考图URL',
    `sort_order`          INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `status`              TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`           BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`         DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`           BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`         DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`              VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_drama_id` (`drama_id`),
    KEY `idx_scene_type` (`scene_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '场景环境资产表';

-- 3.7 核心道具资产表
DROP TABLE IF EXISTS `res_prop`;
CREATE TABLE `res_prop` (
    `id`                  BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`            BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归属短剧ID，0为公共资源库',
    `name`                VARCHAR(100)    NOT NULL COMMENT '道具中文名称 (如: 繁复小座钟、黑色手提箱)',
    `prop_type`           VARCHAR(32)     NOT NULL DEFAULT 'KEY_PROP' COMMENT '道具类型 KEY_PROP(核心叙事道具)/WEAPON(武器)/COSTUME_ACCESSORY(服饰配饰)/DAILY(日常杂物)',
    `description`         VARCHAR(512)    DEFAULT NULL COMMENT '道具中文背景与特征描述',
    `prop_prompt`         VARCHAR(1024)   DEFAULT NULL COMMENT '英文生图/参考图Prompt (如: ornate antique brass desk clock with intricate engravings)',
    `cover_url`           VARCHAR(512)    DEFAULT NULL COMMENT '道具参考图/设计图URL',
    `sort_order`          INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `status`              TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`           BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`         DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`           BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`         DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`              VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_drama_id` (`drama_id`),
    KEY `idx_prop_type` (`prop_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '核心道具资产表';

-- 3.8 关键帧资产表
DROP TABLE IF EXISTS `res_keyframe`;
CREATE TABLE `res_keyframe` (
    `id`                  BIGINT UNSIGNED NOT NULL COMMENT '主键ID (雪花ID)',
    `drama_id`            BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归属短剧ID，0为公共资源库',
    `shot_id`             BIGINT UNSIGNED DEFAULT NULL COMMENT '关联分镜镜头ID (drama_shot.id)，为空表示未绑定具体分镜',
    `name`                VARCHAR(100)    NOT NULL COMMENT '关键帧名称 (如: 雨夜对峙首帧 / 回眸特写关键帧)',
    `frame_type`          VARCHAR(32)     NOT NULL DEFAULT 'COMPOSITION_ANCHOR' COMMENT '关键图默认用途: FIRST_FRAME/KEYFRAME/LAST_FRAME/EDITED_KEYFRAME/COMPOSITION_ANCHOR/STORYBOARD_REFERENCE/SHOT_PLANNING_REFERENCE',
    `frame_url`           VARCHAR(512)    NOT NULL COMMENT '关键帧图片URL (MinIO托管)',
    `prompt`              TEXT            DEFAULT NULL COMMENT '关键帧生图/视觉控制Prompt',
    `negative_prompt`     TEXT            DEFAULT NULL COMMENT '专属负向Prompt',
    `description`         VARCHAR(512)    DEFAULT NULL COMMENT '画面动作、构图与镜头细节描述',
    `source_type`         VARCHAR(32)     NOT NULL DEFAULT 'MANUAL_UPLOAD' COMMENT '来源: MANUAL_UPLOAD(手动上传)/AI_GENERATED(AI生图)/SHOT_EXTRACT(分镜抽取)/VIDEO_FRAME(视频截帧)',
    `aspect_ratio`        VARCHAR(16)     DEFAULT NULL COMMENT '画面画幅比例 (如 16:9, 9:16, 1:1)',
    `sort_order`          INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `status`              TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
    `create_by`           BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`         DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`           BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`         DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`              VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_drama_id` (`drama_id`),
    KEY `idx_shot_id` (`shot_id`),
    KEY `idx_frame_type` (`frame_type`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '关键帧资产表';

-- ==============================================================================
-- 4. 短剧剧作工作台模块：短剧、剧集、情景场次、连续镜头组、分镜镜头
-- ==============================================================================

-- 4.1 短剧项目表
DROP TABLE IF EXISTS `drama`;
CREATE TABLE `drama` (
    `id`              BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `title`           VARCHAR(128)    NOT NULL COMMENT '短剧名称',
    `cover_url`       VARCHAR(512)    DEFAULT NULL COMMENT '短剧封面图URL (MinIO)',
    `genre`           VARCHAR(64)     NOT NULL DEFAULT 'DOMINANT_CEO' COMMENT '题材类型 (字典 drama_genre)',
    `target_episodes` INT             NOT NULL DEFAULT 80 COMMENT '规划目标集数',
    `aspect_ratio`    VARCHAR(16)     NOT NULL DEFAULT '9:16' COMMENT '画幅比例 (9:16/16:9/1:1/4:3)',
    `style_preset`    VARCHAR(64)     NOT NULL DEFAULT 'cinematic-realism' COMMENT '默认风格预设 (字典 drama_style_preset)',
    `style_tone`      VARCHAR(500)    DEFAULT NULL COMMENT '视觉风格基调/导演风格指南（自然语言融入，如光影、色调、镜头质感）',
    `synopsis`        TEXT            DEFAULT NULL COMMENT '短剧故事梗概/大纲',
    `status`          VARCHAR(32)     NOT NULL DEFAULT 'PLANNING' COMMENT '状态 (PLANNING/IN_PROGRESS/COMPLETED/ARCHIVED)',
    `sort_order`      INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `create_by`       BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`     DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`     DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`          VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_title` (`title`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '短剧项目表';

-- 4.2 短剧剧集表
DROP TABLE IF EXISTS `drama_episode`;
CREATE TABLE `drama_episode` (
    `id`              BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`        BIGINT UNSIGNED NOT NULL COMMENT '归属短剧ID',
    `episode_no`      INT             NOT NULL COMMENT '集数序号 (第N集)',
    `title`           VARCHAR(128)    NOT NULL COMMENT '本集标题',
    `summary`         VARCHAR(500)    DEFAULT NULL COMMENT '本集剧情简介',
    `script_content`  LONGTEXT        DEFAULT NULL COMMENT '本集原始剧本文本',
    `target_duration` INT             NOT NULL DEFAULT 90 COMMENT '目标时长 (秒)',
    `actual_duration` DECIMAL(6,2)    NOT NULL DEFAULT 0.00 COMMENT '累计分镜实际时长 (秒)',
    `status`          VARCHAR(32)     NOT NULL DEFAULT 'DRAFT' COMMENT '剧集状态 (DRAFT/SCRIPT_PARSED/SHOTS_READY/RENDERING/COMPLETED)',
    `sort_order`      INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `create_by`       BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`     DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`     DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`          VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_drama_episode` (`drama_id`, `episode_no`),
    KEY `idx_drama_id` (`drama_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '短剧剧集表';

-- 4.3 情景场次表
DROP TABLE IF EXISTS `drama_scene`;
CREATE TABLE `drama_scene` (
    `id`                 BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`           BIGINT UNSIGNED NOT NULL COMMENT '归属短剧ID',
    `episode_id`         BIGINT UNSIGNED NOT NULL COMMENT '归属剧集ID',
    `scene_no`           INT             NOT NULL COMMENT '场次序号 (第N场)',
    `name`               VARCHAR(128)    NOT NULL COMMENT '场次名称 (如: 场次1-酒店大堂主角受辱)',
    `res_scene_id`       BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定的环境场景资产ID (res_scene.id)',
    `scene_type`         VARCHAR(32)     NOT NULL DEFAULT 'INDOOR' COMMENT '空间类型 (INDOOR/OUTDOOR/STUDIO/VIRTUAL)',
    `time_of_day`        VARCHAR(32)     NOT NULL DEFAULT 'DAY' COMMENT '时间时段 (DAY/NIGHT/SUNSET/DAWN)',
    `weather_atmosphere` VARCHAR(64)     DEFAULT NULL COMMENT '天气/氛围 (RAINY/NEON/MOODY...)',
    `location_name`      VARCHAR(100)    DEFAULT NULL COMMENT '地点补充说明',
    `summary`            VARCHAR(500)    DEFAULT NULL COMMENT '场次剧情摘要',
    `script_content`     TEXT            DEFAULT NULL COMMENT '本场剧本台词与动作描述',
    `sort_order`         INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `create_by`          BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`        DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`          BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`        DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`             VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_episode_id` (`episode_id`),
    KEY `idx_drama_id` (`drama_id`),
    KEY `idx_res_scene_id` (`res_scene_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '情景场次表';

-- 4.4 连续镜头组表 (drama_shot_group)
DROP TABLE IF EXISTS `drama_shot_group`;
CREATE TABLE `drama_shot_group` (
    `id`                     BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`               BIGINT UNSIGNED NOT NULL COMMENT '归属短剧ID',
    `episode_id`             BIGINT UNSIGNED NOT NULL COMMENT '归属剧集ID',
    `scene_id`               BIGINT UNSIGNED NOT NULL COMMENT '归属场次ID (drama_scene.id)',
    `group_no`               INT             NOT NULL DEFAULT 1 COMMENT '镜头组序号 (1, 2, 3...)',
    `name`                   VARCHAR(128)    NOT NULL COMMENT '镜头组名称/连续叙事动作描述',
    `purpose`                VARCHAR(255)    DEFAULT NULL COMMENT '导演意图/叙事目的/戏剧张力目标',
    `sort_order`             INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `create_by`              BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`            DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`              BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`            DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`                TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`                 VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_scene_id` (`scene_id`),
    KEY `idx_episode_id` (`episode_id`),
    KEY `idx_drama_id` (`drama_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '连续镜头组表';

-- 4.5 分镜镜头表 (drama_shot)
DROP TABLE IF EXISTS `drama_shot`;
CREATE TABLE `drama_shot` (
    `id`                         BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    `drama_id`                   BIGINT UNSIGNED NOT NULL COMMENT '归属短剧ID',
    `episode_id`                 BIGINT UNSIGNED NOT NULL COMMENT '归属剧集ID',
    `scene_id`                   BIGINT UNSIGNED NOT NULL COMMENT '归属场次ID (drama_scene.id)',
    `shot_group_id`             BIGINT UNSIGNED DEFAULT NULL COMMENT '归属镜头组ID (drama_shot_group.id)',
    `shot_no`                    INT             NOT NULL COMMENT '分镜序号 (1, 2, 3...)',
    `shot_name`                  VARCHAR(128)    NOT NULL COMMENT '镜头标识 (如: S01-01)',
    `shot_type`                  VARCHAR(32)     DEFAULT NULL COMMENT '景别 (字典 shot_type)',
    `camera_movement`            VARCHAR(32)     DEFAULT NULL COMMENT '运镜方式 (字典 camera_movement)',
    `shot_type_locked`           TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '景别是否由创作者明确锁定',
    `camera_movement_locked`     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '运镜是否由创作者明确锁定',
    `duration`                   DECIMAL(4,2)    NOT NULL DEFAULT 3.00 COMMENT '预估镜头时长 (秒)',
    `seed`                       BIGINT          NOT NULL COMMENT '镜头视频渲染随机种子（JavaScript 安全整数）',
    `script_content`             TEXT            DEFAULT NULL COMMENT '本镜头剧本文本 (供后续AI参考原文与剧本生成Prompt)',
    `action_description`         TEXT            DEFAULT NULL COMMENT '画面动作与视觉描述',
    `dialogue`                   TEXT            DEFAULT NULL COMMENT '对白台词',
    `dialogue_speaker`           VARCHAR(64)     DEFAULT NULL COMMENT '台词说话人',
    `voiceover`                  TEXT            DEFAULT NULL COMMENT '旁白/内心独白',
    `sound_effect`               VARCHAR(255)    DEFAULT NULL COMMENT '音效描述',
    `res_scene_id`               BIGINT UNSIGNED DEFAULT NULL COMMENT '环境场景资产ID (为空则继承场次设置)',
    `res_keyframe_id`            BIGINT UNSIGNED DEFAULT NULL COMMENT '关联关键帧资产ID (res_keyframe.id)，与 res_scene_id 互斥',
    `custom_scene_prompt`        TEXT            DEFAULT NULL COMMENT '自定义场景提示词覆盖',
    `character_refs_json`        TEXT            DEFAULT NULL COMMENT '多角色引用 JSON 数组 (含角色ID、造型ID、动作、表情、站位)',
    `prop_refs_json`             TEXT            DEFAULT NULL COMMENT '关联道具引用 JSON 数组 (含道具ID、名称、类型、提示词)',
    `prompt`                     TEXT            DEFAULT NULL COMMENT '最终正向提示词 (由 PromptAssemble 组装或手写)',
    `first_frame_prompt`         TEXT            DEFAULT NULL COMMENT '首帧生图专属提示词 (专供首帧 T2I 生图)',
    `end_frame_prompt`           TEXT            DEFAULT NULL COMMENT '尾帧提示词 (专供尾帧 T2I 生图)',
    `negative_prompt`            TEXT            DEFAULT NULL COMMENT '最终负向提示词',
    `video_prompt`               TEXT            DEFAULT NULL COMMENT '视频动态运镜提示词 (I2V 自然语言指令)',
    `director_plan_json`         TEXT            DEFAULT NULL COMMENT '结构化导演决策计划 JSON (DirectorPlan)',
    `generation_mode`            VARCHAR(32)     NOT NULL DEFAULT 'FIRST_LAST_FRAME' COMMENT '生成模式: FIRST_LAST_FRAME(首尾帧模式)/REFERENCE_MODE(参考图音频模式)',
    `end_frame_image_url`        VARCHAR(512)    DEFAULT NULL COMMENT '尾帧图URL (MinIO URL)',
    `ref_images_json`            TEXT            DEFAULT NULL COMMENT '参考图列表 JSON (上限5个)',
    `ref_audios_json`            TEXT            DEFAULT NULL COMMENT '参考音频列表 JSON (上限4个, 单段3-8s, 总长20-30s)',
    `last_frame_url`             VARCHAR(512)    DEFAULT NULL COMMENT '本镜生成视频末帧图 (MinIO URL, 供组内下一镜首帧续接)',
    `last_frame_source_video_url` VARCHAR(512)   DEFAULT NULL COMMENT 'last_frame_url 对应的视频版本 URL，用于缓存失效判断',
    `last_frame_source_take_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT 'last_frame_url 对应的视频版本 Take ID，用于缓存失效判断',
    `style_preset`               VARCHAR(64)     DEFAULT NULL COMMENT '风格预设 (为空则继承短剧全局风格)',
    `preview_image_url`          VARCHAR(512)    DEFAULT NULL COMMENT '首帧/关键帧预览图 (MinIO URL)',
    `first_frame_source_type`    VARCHAR(32)     DEFAULT NULL COMMENT '首帧来源: MANUAL_UPLOAD/AI_GENERATED/PREVIOUS_VIDEO_TAIL',
    `first_frame_source_shot_id` BIGINT          DEFAULT NULL COMMENT '首帧来自上一镜视频尾帧时的来源分镜ID',
    `first_frame_source_video_url` VARCHAR(512)  DEFAULT NULL COMMENT '首帧引用时对应的来源视频版本URL',
    `first_frame_source_video_take_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '首帧来自上一镜视频尾帧时的来源 Take ID',
    `video_url`                  VARCHAR(512)    DEFAULT NULL COMMENT '最终生成视频 (MinIO URL)',
    `current_video_take_id`      BIGINT UNSIGNED DEFAULT NULL COMMENT '当前选中的分镜视频Take ID',
    `audio_url`                  VARCHAR(512)    DEFAULT NULL COMMENT 'TTS配音音频 (MinIO URL)',
    `render_status`              VARCHAR(32)     NOT NULL DEFAULT 'INIT' COMMENT '渲染状态 (INIT/QUEUED/RENDERING/SUCCESS/FAILED)',
    `latest_task_id`             VARCHAR(64)     DEFAULT NULL COMMENT '关联最新 ComfyUI 任务 ID',
    `comfy_workflow_template_id` VARCHAR(64)     DEFAULT NULL COMMENT '指定的 ComfyUI 工作流模板',
    `sort_order`                 INT             NOT NULL DEFAULT 0 COMMENT '显示排序',
    `create_by`                  BIGINT          DEFAULT NULL COMMENT '创建人 ID',
    `create_time`                DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`                  BIGINT          DEFAULT NULL COMMENT '更新人 ID',
    `update_time`                DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`                    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除',
    `remark`                     VARCHAR(500)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY `idx_scene_id` (`scene_id`),
    KEY `idx_shot_group_id` (`shot_group_id`),
    KEY `idx_episode_id` (`episode_id`),
    KEY `idx_drama_id` (`drama_id`),
    KEY `idx_res_keyframe_id` (`res_keyframe_id`),
    KEY `idx_render_status` (`render_status`),
    KEY `idx_generation_mode` (`generation_mode`),
    KEY `idx_current_video_take_id` (`current_video_take_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '分镜镜头表';

-- 4.6 分镜视频抽卡候选版本表 (drama_shot_video_take)
DROP TABLE IF EXISTS `drama_shot_video_take`;
CREATE TABLE `drama_shot_video_take` (
    `id`                    BIGINT UNSIGNED NOT NULL COMMENT '主键ID（雪花算法）',
    `drama_id`              BIGINT UNSIGNED NOT NULL COMMENT '短剧ID（查询冗余）',
    `episode_id`            BIGINT UNSIGNED DEFAULT NULL COMMENT '剧集ID（查询冗余）',
    `scene_id`              BIGINT UNSIGNED DEFAULT NULL COMMENT '场次ID（查询冗余）',
    `shot_group_id`         BIGINT UNSIGNED DEFAULT NULL COMMENT '生成时镜头组ID快照',
    `shot_id`               BIGINT UNSIGNED NOT NULL COMMENT '所属分镜ID',
    `take_no`               INT NOT NULL COMMENT '该分镜下从1开始递增的候选编号',
    `task_id`               VARCHAR(64) DEFAULT NULL COMMENT '来源渲染任务业务ID',
    `source_type`           VARCHAR(32) NOT NULL DEFAULT 'AI_GENERATED'
                              COMMENT '来源: AI_GENERATED/LEGACY_BACKFILL',
    `status`                VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE'
                              COMMENT '资产状态: AVAILABLE/UNAVAILABLE/ARCHIVED',
    `video_url`             VARCHAR(512) NOT NULL COMMENT 'MinIO视频访问URL',
    `provider_id`           BIGINT UNSIGNED DEFAULT NULL COMMENT 'AI提供商ID快照',
    `provider_name`         VARCHAR(128) DEFAULT NULL COMMENT 'AI提供商名称快照',
    `model_code`            VARCHAR(128) DEFAULT NULL COMMENT '视频模型代码快照',
    `generation_mode`       VARCHAR(32) DEFAULT NULL COMMENT '生成模式快照',
    `seed`                  BIGINT DEFAULT NULL COMMENT '随机种子快照',
    `size`                  VARCHAR(32) DEFAULT NULL COMMENT '生成尺寸快照',
    `duration`              DECIMAL(8,2) DEFAULT NULL COMMENT '目标/实际时长秒数',
    `prompt_snapshot`       LONGTEXT DEFAULT NULL COMMENT '视频Prompt快照',
    `negative_prompt_snapshot` TEXT DEFAULT NULL COMMENT '负向Prompt快照',
    `first_frame_url`       VARCHAR(512) DEFAULT NULL COMMENT '生成时首帧URL快照',
    `end_frame_url`         VARCHAR(512) DEFAULT NULL COMMENT '生成时尾帧URL快照',
    `ref_images_json`       LONGTEXT DEFAULT NULL COMMENT '生成时参考图快照',
    `ref_audios_json`       LONGTEXT DEFAULT NULL COMMENT '生成时参考音频快照',
    `request_snapshot_json` LONGTEXT DEFAULT NULL COMMENT '规范化后的请求参数快照JSON',
    `create_by`             BIGINT DEFAULT 0 COMMENT '创建人',
    `create_time`           DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`             BIGINT DEFAULT 0 COMMENT '更新人',
    `update_time`           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `remark`                VARCHAR(500) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_shot_take_no` (`shot_id`, `take_no`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_shot_status_time` (`shot_id`, `status`, `create_time`),
    KEY `idx_drama_episode` (`drama_id`, `episode_id`),
    KEY `idx_scene_id` (`scene_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='分镜视频抽卡候选版本表';

-- ==============================================================================
-- 5. 初始化字典数据与枚举预设 (Seed Dictionaries)
-- ==============================================================================

-- 5.1 通用字典类型
REPLACE INTO `sys_dict_type` (`id`, `dict_type`, `dict_name`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`, `remark`)
VALUES
(1, 'sys_normal_disable', '系统正常/停用状态', 1, 0, NOW(), 0, NOW(), 0, '通用启用/停用'),
(10, 'res_role_type', '角色定位分类', 1, 0, NOW(), 0, NOW(), 0, '主角/反派/配角/路人'),
(11, 'res_gender', '角色性别', 1, 0, NOW(), 0, NOW(), 0, '男/女/其他/未知'),
(12, 'res_scene_type', '场景空间类型', 1, 0, NOW(), 0, NOW(), 0, '室内/室外/影棚/虚构'),
(13, 'res_time_of_day', '场景时段', 1, 0, NOW(), 0, NOW(), 0, '日间/夜间/黄昏/拂晓'),
(14, 'res_identity_status', '角色身份状态', 1, 0, NOW(), 0, NOW(), 0, '未知/临时称谓/已确认/未决/已合并'),
(15, 'res_alias_type', '角色别名类型', 1, 0, NOW(), 0, NOW(), 0, '正式名/代词/描述/头衔/昵称/职业/其他'),
(16, 'res_evidence_type', '身份消歧依据类型', 1, 0, NOW(), 0, NOW(), 0, '初次登场/自我介绍/指名/称呼/剧情关系/外貌/连续性/其他'),
(17, 'res_prop_type', '道具类型分类', 1, 0, NOW(), 0, NOW(), 0, '核心叙事道具/武器装备/服饰配饰/日常杂物'),
(20, 'drama_genre', '短剧题材类型', 1, 0, NOW(), 0, NOW(), 0, '霸总/战神/穿越/古装/悬疑等'),
(21, 'drama_aspect_ratio', '画面画幅比例', 1, 0, NOW(), 0, NOW(), 0, '9:16/16:9/1:1/4:3'),
(22, 'drama_style_preset', '画面风格预设', 1, 0, NOW(), 0, NOW(), 0, '电影写实/3D动画/二次元/赛博朋克等'),
(23, 'drama_status', '短剧项目状态', 1, 0, NOW(), 0, NOW(), 0, '筹备中/制作中/已完结/已归档'),
(24, 'episode_status', '剧集状态', 1, 0, NOW(), 0, NOW(), 0, '草稿/剧本已拆解/分镜就绪/渲染中/已完成'),
(25, 'shot_type', '镜头景别分类', 1, 0, NOW(), 0, NOW(), 0, '大特写/特写/中特写/中景/全景/远景/过肩/俯拍'),
(26, 'camera_movement', '镜头运镜方式', 1, 0, NOW(), 0, NOW(), 0, '固定/推/拉/摇/移/升降/环绕/变焦'),
(27, 'shot_render_status', '分镜渲染状态', 1, 0, NOW(), 0, NOW(), 0, '未渲染/排队中/渲染中/成功/失败'),
(29, 'ai_model_type', 'AI模型类型分类', 1, 0, NOW(), 0, NOW(), 0, 'CHAT(对话)/TXT2IMG(文生图)/IMG2IMG(图生图)/TXT_IMG2IMG(文生图+图生图)/TXT2VIDEO_FIRST_LAST(文生视频/首尾帧参考)/TXT2VIDEO_REF(文生视频/图参考)/TTS(TTS语音)/LIP_SYNC(音画同步)/EMBEDDING(向量)/VIDEO_UPSCALE(视频超分)/FRAME_INTERPOLATION(视频补帧)'),
(30, 'shot_generation_mode', '分镜画面生成模式', 1, 0, NOW(), 0, NOW(), 0, 'FIRST_LAST_FRAME(首尾帧模式)/REFERENCE_MODE(参考图与音频模式)');

-- 5.2 字典明细数据项
REPLACE INTO `sys_dict_data` (`id`, `dict_type`, `dict_label`, `dict_value`, `sort_order`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`, `remark`)
VALUES
-- 通用启停
(1, 'sys_normal_disable', '停用', '0', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(2, 'sys_normal_disable', '启用', '1', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 资源库角色
(101, 'res_role_type', '主角', 'PROTAGONIST', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(102, 'res_role_type', '反派', 'ANTAGONIST', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(103, 'res_role_type', '配角', 'SUPPORTING', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(104, 'res_role_type', '路人/群演', 'EXTRA', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 性别
(111, 'res_gender', '男 (Male)', 'MALE', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(112, 'res_gender', '女 (Female)', 'FEMALE', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(113, 'res_gender', '其他 (Other)', 'OTHER', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(114, 'res_gender', '未知 (Unknown)', 'UNKNOWN', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 场景空间类型
(121, 'res_scene_type', '室内 (Indoor)', 'INDOOR', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(122, 'res_scene_type', '室外 (Outdoor)', 'OUTDOOR', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(123, 'res_scene_type', '摄影棚 (Studio)', 'STUDIO', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(124, 'res_scene_type', '虚构/奇幻 (Virtual)', 'VIRTUAL', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 场景时段
(131, 'res_time_of_day', '日间 (Day)', 'DAY', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(132, 'res_time_of_day', '夜间 (Night)', 'NIGHT', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(133, 'res_time_of_day', '黄昏 (Sunset)', 'SUNSET', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(134, 'res_time_of_day', '拂晓 (Dawn)', 'DAWN', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 角色身份状态
(141, 'res_identity_status', '未知身份 (Unknown)', 'UNKNOWN', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(142, 'res_identity_status', '临时称谓未定名 (Partial)', 'PARTIAL', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(143, 'res_identity_status', '已确认正式名 (Confirmed)', 'CONFIRMED', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(144, 'res_identity_status', '待消歧决议 (Unresolved)', 'UNRESOLVED', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(145, 'res_identity_status', '已合并 (Merged)', 'MERGED', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 别名类型
(151, 'res_alias_type', '正式名/化名 (Name)', 'NAME', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(152, 'res_alias_type', '代词 (Pronoun)', 'PRONOUN', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(153, 'res_alias_type', '外貌特征描述 (Description)', 'DESCRIPTION', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(154, 'res_alias_type', '头衔/尊称 (Title)', 'TITLE', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(155, 'res_alias_type', '昵称/小名 (Nickname)', 'NICKNAME', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),
(156, 'res_alias_type', '职业/身份 (Role)', 'ROLE', 6, 1, 0, NOW(), 0, NOW(), 0, NULL),
(157, 'res_alias_type', '其他 (Other)', 'OTHER', 7, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 证据类型
(161, 'res_evidence_type', '首次出场 (First Appearance)', 'FIRST_APPEARANCE', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(162, 'res_evidence_type', '明确自我介绍 (Self Introduction)', 'SELF_INTRODUCTION', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(163, 'res_evidence_type', '剧本明确指名 (Explicit Name)', 'EXPLICIT_NAME', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(164, 'res_evidence_type', '他人明确称呼 (Explicit Reference)', 'EXPLICIT_REFERENCE', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(165, 'res_evidence_type', '人物剧情关系 (Relationship)', 'RELATIONSHIP', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),
(166, 'res_evidence_type', '外貌特征吻合 (Appearance Match)', 'APPEARANCE_MATCH', 6, 1, 0, NOW(), 0, NOW(), 0, NULL),
(167, 'res_evidence_type', '上下文语义推理 (Context Match)', 'CONTEXT_MATCH', 7, 1, 0, NOW(), 0, NOW(), 0, NULL),
(168, 'res_evidence_type', '场景剧情连续性 (Scene Continuity)', 'SCENE_CONTINUITY', 8, 1, 0, NOW(), 0, NOW(), 0, NULL),
(169, 'res_evidence_type', '其他依据 (Other)', 'OTHER', 9, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 道具分类
(171, 'res_prop_type', '核心叙事道具 (Key Prop)', 'KEY_PROP', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(172, 'res_prop_type', '武器装备 (Weapon)', 'WEAPON', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(173, 'res_prop_type', '服饰配饰 (Accessory)', 'COSTUME_ACCESSORY', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(174, 'res_prop_type', '日常杂物 (Daily)', 'DAILY', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 短剧题材
(201, 'drama_genre', '霸总甜宠', 'DOMINANT_CEO', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(202, 'drama_genre', '战神回归', 'WAR_GOD', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(203, 'drama_genre', '都市异能', 'URBAN_ABILITY', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(204, 'drama_genre', '穿越重生', 'TIME_TRAVEL', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(205, 'drama_genre', '古装仙侠', 'ANCIENT_COSTUME', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),
(206, 'drama_genre', '悬疑惊悚', 'SUSPENSE', 6, 1, 0, NOW(), 0, NOW(), 0, NULL),
(207, 'drama_genre', '幽默搞笑', 'COMEDY', 7, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 画幅比例
(211, 'drama_aspect_ratio', '9:16 (竖屏短剧)', '9:16', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(212, 'drama_aspect_ratio', '16:9 (横屏剧集)', '16:9', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(213, 'drama_aspect_ratio', '1:1 (方形画幅)', '1:1', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(214, 'drama_aspect_ratio', '4:3 (经典画幅)', '4:3', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 画面风格预设
(221, 'drama_style_preset', '电影级写实 (Cinematic Realism)', 'cinematic-realism', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(222, 'drama_style_preset', '3D 精美动画 (3D Animation)', '3d-animation', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(223, 'drama_style_preset', '2D 动漫 (2D Anime)', 'anime-2d', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(224, 'drama_style_preset', '赛博朋克写实 (Cyberpunk)', 'cyber-realism', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(225, 'drama_style_preset', '复古胶片感 (Retro Film)', 'retro-film', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),
(226, 'drama_style_preset', '2D 动漫兼容 (Anime Makoto)', 'anime-makoto', 6, 0, 0, NOW(), 0, NOW(), 0, '旧版键名兼容'),

-- 短剧状态
(231, 'drama_status', '筹备中 (Planning)', 'PLANNING', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(232, 'drama_status', '制作中 (In Progress)', 'IN_PROGRESS', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(233, 'drama_status', '已完结 (Completed)', 'COMPLETED', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(234, 'drama_status', '已归档 (Archived)', 'ARCHIVED', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 剧集状态
(241, 'episode_status', '草稿 (Draft)', 'DRAFT', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(242, 'episode_status', '剧本已拆解 (Parsed)', 'SCRIPT_PARSED', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(243, 'episode_status', '分镜就绪 (Shots Ready)', 'SHOTS_READY', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(244, 'episode_status', '渲染中 (Rendering)', 'RENDERING', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(245, 'episode_status', '已完成 (Completed)', 'COMPLETED', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 景别
(250, 'shot_type', '自动决定 (AUTO)', 'AUTO', 0, 1, 0, NOW(), 0, NOW(), 0, '由 AI 导演自主决定景别'),
(251, 'shot_type', '大特写 (Extreme Close-Up)', 'EXTREME_CLOSE_UP', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(252, 'shot_type', '特写 (Close-Up)', 'CLOSE_UP', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(253, 'shot_type', '中特写 (Medium Close-Up)', 'MEDIUM_CLOSE_UP', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(254, 'shot_type', '中景 (Medium Shot)', 'MEDIUM_SHOT', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(255, 'shot_type', '全景 (Full Shot)', 'FULL_SHOT', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),
(256, 'shot_type', '远景 (Long Shot)', 'LONG_SHOT', 6, 1, 0, NOW(), 0, NOW(), 0, NULL),
(257, 'shot_type', '过肩镜头 (Over-the-Shoulder)', 'OVER_SHOULDER', 7, 1, 0, NOW(), 0, NOW(), 0, NULL),
(258, 'shot_type', '俯拍/鸟瞰 (Top-Down / Bird Eye)', 'TOP_DOWN', 8, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 运镜
(260, 'camera_movement', '自动运镜 (AUTO)', 'AUTO', 0, 1, 0, NOW(), 0, NOW(), 0, '由 AI 导演自主决定运镜节奏与 Camera Beats'),
(261, 'camera_movement', '固定镜头 (Static)', 'STATIC', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(262, 'camera_movement', '推镜头 (Push In)', 'PUSH_IN', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(263, 'camera_movement', '拉镜头 (Pull Out)', 'PULL_OUT', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(264, 'camera_movement', '左摇 (Pan Left)', 'PAN_LEFT', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(265, 'camera_movement', '右摇 (Pan Right)', 'PAN_RIGHT', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),
(266, 'camera_movement', '仰摇 (Tilt Up)', 'TILT_UP', 6, 1, 0, NOW(), 0, NOW(), 0, NULL),
(267, 'camera_movement', '俯摇 (Tilt Down)', 'TILT_DOWN', 7, 1, 0, NOW(), 0, NOW(), 0, NULL),
(268, 'camera_movement', '跟随追踪 (Tracking)', 'TRACKING', 8, 1, 0, NOW(), 0, NOW(), 0, NULL),
(269, 'camera_movement', '环绕旋转 (Orbit)', 'ORBIT', 9, 1, 0, NOW(), 0, NOW(), 0, NULL),
(270, 'camera_movement', '快速变焦 (Zoom In)', 'ZOOM_IN', 10, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- 分镜渲染状态
(281, 'shot_render_status', '未渲染 (Init)', 'INIT', 1, 1, 0, NOW(), 0, NOW(), 0, NULL),
(282, 'shot_render_status', '排队中 (Queued)', 'QUEUED', 2, 1, 0, NOW(), 0, NOW(), 0, NULL),
(283, 'shot_render_status', '渲染中 (Rendering)', 'RENDERING', 3, 1, 0, NOW(), 0, NOW(), 0, NULL),
(284, 'shot_render_status', '渲染完成 (Success)', 'SUCCESS', 4, 1, 0, NOW(), 0, NOW(), 0, NULL),
(285, 'shot_render_status', '渲染失败 (Failed)', 'FAILED', 5, 1, 0, NOW(), 0, NOW(), 0, NULL),

-- AI 模型类型
(301, 'ai_model_type', '文本对话 (CHAT)', 'CHAT', 1, 1, 0, NOW(), 0, NOW(), 0, '纯文本对话、剧本拆解、实体消歧模型'),
(302, 'ai_model_type', '文生图 (TXT2IMG)', 'TXT2IMG', 2, 1, 0, NOW(), 0, NOW(), 0, '纯文字生成图片模型，不支持参考图输入（如 DALL-E 3、标准 SDXL）'),
(303, 'ai_model_type', '图生图 (IMG2IMG)', 'IMG2IMG', 3, 1, 0, NOW(), 0, NOW(), 0, '纯图像转绘、滤镜或线稿上色模型，必须依赖输入图像'),
(304, 'ai_model_type', '文生图/图生图 (TXT_IMG2IMG)', 'TXT_IMG2IMG', 4, 1, 0, NOW(), 0, NOW(), 0, '双模生图模型，支持纯文本生图，同时支持多参考图引导（如 FLUX.2 Klein 9B）'),
(305, 'ai_model_type', '向量嵌入 (EMBEDDING)', 'EMBEDDING', 5, 1, 0, NOW(), 0, NOW(), 0, '文本特征向量化模型'),
(306, 'ai_model_type', '文生视频/首尾帧参考 (TXT2VIDEO_FIRST_LAST)', 'TXT2VIDEO_FIRST_LAST', 6, 1, 0, NOW(), 0, NOW(), 0, '文本+可选首帧/首尾帧参考生成连贯视频模型（如 MiniMax H3 FL2VA-Fast）'),
(307, 'ai_model_type', '文生视频/图参考 (TXT2VIDEO_REF)', 'TXT2VIDEO_REF', 7, 1, 0, NOW(), 0, NOW(), 0, '文本+多张参考图特征引导生成连贯视频模型（如 MiniMax H3 Ref2VA-Fast）'),
(308, 'ai_model_type', 'TTS语音模型 (TTS)', 'TTS', 8, 1, 0, NOW(), 0, NOW(), 0, '角色台词配音与文本转语音合成模型（如 Edge-TTS、CosyVoice、F5-TTS）'),
(309, 'ai_model_type', '音画同步模型 (LIP_SYNC)', 'LIP_SYNC', 9, 1, 0, NOW(), 0, NOW(), 0, '角色视频与配音音频口型同步对齐模型（如 LatentSync、LivePortrait、Wav2Lip）'),
(310, 'ai_model_type', '视频超分模型 (VIDEO_UPSCALE)', 'VIDEO_UPSCALE', 10, 1, 0, NOW(), 0, NOW(), 0, '视频画质增强与超分模型（如 RealESRGAN x2plus 等）'),
(313, 'ai_model_type', '视频补帧模型 (FRAME_INTERPOLATION)', 'FRAME_INTERPOLATION', 11, 1, 0, NOW(), 0, NOW(), 0, '视频补帧与平滑插帧模型（如 RIFE 4.9 等）'),

-- 分镜生成模式
(311, 'shot_generation_mode', '首尾帧模式', 'FIRST_LAST_FRAME', 1, 1, 0, NOW(), 0, NOW(), 0, '仅支持首帧图、尾帧图和提示词作为参考'),
(312, 'shot_generation_mode', '参考图与音频模式', 'REFERENCE_MODE', 2, 1, 0, NOW(), 0, NOW(), 0, '支持最多5张参考图与4段参考音频');

-- ==============================================================================
-- 6. 初始化核心系统常量与 AI 阶段提示词种子 (Sys Config)
-- ==============================================================================

REPLACE INTO `sys_config`
(`id`, `config_name`, `config_key`, `config_value`, `config_type`, `is_builtin`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`, `remark`)
VALUES
(3, '分镜画质基础负向词', 'res.prompt.default_base_negative',
'(worst quality, low quality:1.4), (deformed, distorted, disfigured:1.3), poorly drawn, bad anatomy, wrong anatomy, extra limb, missing limb, floating limbs, (mutated hands and fingers:1.4), disconnected limbs, mutation, blurry, watermark, text, signature',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, '分镜生图组装的基础负向提示词 (Negative Prompt)'),

(6, 'Planner AI章节剧情分段与角色提取提示词', 'ai.prompt.planner_system',
'你是本系统的影视剧本结构分析与叙事规划 AI（Chapter Planner）。你的职责是从完整章节、剧本或叙事文本中识别稳定资产、理解时空结构与剧情推进，并将原文划分为结构清晰、语义完整、可供后续 Worker 继续拆解的 Story Segment。你不负责镜头设计、摄影设计、动作执行设计、视频生成提示词或 MiniMax H3 Prompt 编写。
【核心职责】
1.理解整个章节的剧情发展、人物关系、事件因果、时空变化、场景结构和主要情绪变化。
2.识别并整理当前章节需要使用的角色、场景和核心活动道具资产。
3.对角色身份进行识别、消歧和引用关系确认。
4.对场景资产进行身份判断和已有资产复用。
5.按完整剧情事件和时空连续性划分 Story Segment。
6.为每个 Segment 提炼足够支持 Worker 继续分镜编排的剧情语境、空间状态、人物关系、情绪趋势和资产引用。
7.保持原剧情事实，不新增剧情、人物关系、事件结果或原文不存在的重要设定。
【Skill加载】
Planner 阶段必须加载 character-disambiguation Skill，并使用其规则完成人物身份识别、同名异人判断、别名统一、代词指代和角色引用消歧。Java 仅负责校验角色 ID 和数据合法性，不负责推断、替换或修正人物身份。不得声称加载未成功的 Skill。
【职责边界】
Planner 只负责章节级结构，不负责 Shot 级执行。不得生成 Shot、ShotGroup、Camera、Duration、景别、构图、运镜、焦段、screen-space 关系或 Video Prompt。不得把剧情动作改写成视频模型执行语言。不得替 Worker 决定单镜头动作密度、动作阶段、摄影观察关系或镜头持续时间。不得替 Prompt AI 编写 MiniMax H3 相关内容。Planner 输出的是稳定的剧情结构和资产上下文，而不是最终视觉方案。
【剧情事实原则】
所有分析必须以当前原文为事实来源。可以进行必要的语义归纳、指代解析、资产识别和时空整理，但不得改变人物、地点、事件、因果关系、事件顺序或事件结果。原文没有明确的信息不得作为确定事实补写。可以为角色资产进行必要的稳定外貌概括，但不得借资产设计扩写剧情。必须区分稳定资产属性与当前剧情中的临时状态。
【Story Segment 划分】
Segment 必须以完整、连续、具有明确叙事意义的剧情事件为单位。不得机械按照字数、段落、句子或标点切分。不得在尚未完成的动作、连续对话、冲突、追逐、交互或因果链中间无理由切断。当剧情发生明显的空间变化、时间变化、事件阶段变化、核心人物组合变化、叙事重点变化、叙事层级变化或一个完整事件结束时，可以形成新的 Segment。剧情完整性和连续性优先于固定长度。Segment 不要求长度一致，也不得为了满足长度目标破坏完整事件。
【Segment Summary】
每个 Segment 的 summary 必须是面向 Worker 的剧情状态摘要，而不是原文复述。summary 应提炼当前事件正在发生什么、事件开始时的重要状态、事件如何推进、本段结束时形成什么新的剧情状态、当前主要人物及其关系、人物当前目标态度和情绪趋势、人物之间的重要距离位置行动关系或互动关系、当前场景的空间性质和主要环境条件、剧情中已经明确存在的重要环境状态、当前涉及的重要活动道具及其归属状态和用途、后续 Segment 或 Worker 必须继承的重要连续性信息。summary 只描述剧情和可继承状态，不设计具体镜头表现。
【时空连续性】
必须识别每个 Segment 所处的时间、地点和空间状态。应保持人物、场景、道具和事件在 Segment 之间的连续继承。如果人物、道具、位置关系、时间状态或事件结果已经发生改变，后续 Segment 必须从改变后的状态继续。不得无依据重置人物位置、关系、道具状态或已经完成的事件。对于原文未明确发生变化的稳定状态，应默认继续继承。
【角色资产识别】
只提取具有稳定身份并对当前剧情有实际叙事作用的角色。角色是否成为资产应根据其独立身份、剧情参与度和后续复用价值判断，而不是单纯根据是否在文本中被提及。纯背景性、泛指性、不可稳定识别或仅承担环境填充作用的对象不应创建为命名角色资产。仅被提及但没有实际进入当前叙事空间的角色，可以参与语义理解，但不应因此自动成为当前 Segment 的在场角色。旁白、描述性声音或其他非实体叙事来源不得自动创建为人物资产。
【角色身份与消歧】
角色名称、别名、称谓、代词和上下文指代必须统一解析到正确角色。已有角色资产应优先复用，不得因名称变化、称谓变化或描述变化重复创建角色。只有证据足以确认是不同人物时，才建立新的角色资产。characterIds 必须使用实际中文角色名称，不得使用无语义占位编号代替角色名称。数据库角色 ID 只能引用系统提供的有效 ID，不得自行生成或猜测。
【稳定外貌资产】
appearanceDesc 只描述角色长期稳定、跨场景可复用的视觉身份特征，例如面部特征、发型、体态和其他稳定生理特征。不得把当前 Segment 中的姿势、位置、动作、表情变化、受力状态或临时环境影响写入稳定外貌。不得生成服装资产或把当前服装混入 appearanceDesc。当原文缺少部分稳定视觉信息而系统允许进行资产概念化时，只允许进行最小必要补全，并保持与项目整体视觉设定一致。appearancePrompt 必须为 null。outfitPrompt 必须为 null。
【场景资产识别与复用】
场景资产代表稳定的物理空间，而不是某一次剧情状态。判断两个场景是否属于同一资产时，应综合地点身份、空间结构、固定环境组成和叙事上下文，而不是仅比较名称。同一物理空间在不同时间、天气、照明、人物状态或剧情阶段下原则上仍复用同一个场景资产，除非其物理空间本身已经发生足以形成独立资产的变化。已有场景能够确认复用时，填写已有数据库场景 ID 到 existingSceneId，并使用登记场景名称。无法可靠确认时保持 existingSceneId 为空。不得猜测或编造数据库场景 ID。
【场景资产内容】
场景资产仅负责识别和维护稳定的物理地点身份、空间结构及固定环境组成。固定环境组成应归属于场景，不应拆成活动道具。剧情中的临时人物动作、临时可移动物品、事件结果和单次状态不得污染场景资产。时间、天气、照明和事件造成的临时环境状态应根据剧情需要记录在 Segment summary 中，但不得因此自动创建新的场景资产。
【活动道具资产】
只提取具有独立身份、可移动、能够被人物持有或操作，并且对剧情具有实际作用或后续复用价值的物品。环境结构、建筑组成和固定陈设属于场景，不创建为独立活动道具。普通无叙事意义的环境杂物不需要创建资产。同一物品跨 Segment 出现时必须保持同一资产身份，不得因为位置、持有人或状态变化重复创建。
【资产与 Segment 绑定】
每个 Segment 必须指定当前主要场景 sceneId。每个 Segment 必须列出当前剧情实际涉及的重要角色 characterIds。每个 Segment 必须列出当前剧情实际涉及的重要活动道具 propIds。不得为了资产完整性把当前 Segment 未出现或未参与事件的角色和道具强行绑定进来。Segment 中引用的本地 sceneId、propId 必须来自当前输出中已经定义的资产。
【场景局部编号】
本轮新识别的场景使用当前任务要求的局部 scene id。局部 id 只用于本次 Planner 输出内部引用，不代表数据库永久 ID。sceneName 使用清晰、稳定、能够表达物理地点身份的中文名称。locationIds 使用实际中文地点名称或系统要求的有效地点标识，不得使用无语义占位名称代替地点身份。
【原文定位】
不得在 segments 中复制完整原文。每个 Segment 只输出能够唯一定位分段边界所需的 startSnippet、endSnippet，以及系统要求的 startOffset、endOffset。startSnippet 和 endSnippet 应来自原文边界附近，并足够支持 Java 对原文进行准确切片。不得为了 summary 完整而复制大段原文。
【上下游分工】
Planner 决定章节由哪些完整剧情事件组成，以及这些事件涉及哪些稳定资产和可继承剧情状态。Worker 根据 Planner 提供的 Segment 和原文切片进一步拆分 ShotGroup 与 Shot，并负责人物动作、空间状态、道具状态和镜头间剧情连续性。Prompt AI 根据 Worker 已确定的 Shot 事实和参考资产完成视觉导演与 MiniMax H3 Prompt 编译。Planner 不得提前承担 Worker 或 Prompt AI 的职责。
【输出原则】
只输出当前 OUTPUT_FORMAT 明确要求的字段。不得增加解释性字段、调试信息、推理过程或未要求的数据结构。所有 ID、数组和引用必须保持内部一致。不确定的信息应保持为空或使用 OUTPUT_FORMAT 允许的不确定表达，不得通过猜测补齐。
【最终检查】
输出前必须确认 character-disambiguation Skill 已成功加载并应用；没有机械按字数或句子切分剧情；每个 Segment 均构成完整且连续的剧情阶段；没有生成 Shot、ShotGroup、Camera、Duration 或 Video Prompt；没有复制大段原文；角色身份和引用关系不存在明显冲突；没有把仅被提及的对象误判为当前在场角色；稳定角色外貌没有混入动作、位置或服装；appearancePrompt 与 outfitPrompt 均为 null；场景资产代表物理空间而不是临时剧情状态；能够复用的已有场景已正确复用；没有编造数据库资产 ID；固定环境组成没有错误拆成活动道具；活动道具具有明确剧情作用和稳定身份；每个 Segment 的角色、场景和道具引用均与剧情实际内容一致；Segment summary 已包含下游继续工作所需的剧情状态和连续性信息，但没有越权进行镜头设计；所有输出均符合 OUTPUT_FORMAT。任一检查失败时必须重新整理后再输出。',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'Spring AI Planner 章节大纲与剧情分段系统提示词'),

(8, 'Worker AI分镜生成系统提示词', 'ai.prompt.shot_worker_system',
'你是一名剧情分镜编排 AI（Story Shot Screenwriter）。你的任务是将当前 Story Segment 与对应原文整理为连续的 ShotGroup 与 Shot，并生成每个 Shot 的 scriptContent。执行前必须加载 camera-direction Skill，并严格应用其中的 ShotGroup/Shot 拆分、动作负载、自然动作、三维空间拓扑、人物与道具状态、事件因果和连续性规则。你负责明确剧情世界中真实发生的事情，包括人物动作、表情与视线、所在区域、身体朝向、真实移动方向、人物之间的位置与距离变化、道具状态、事件推进以及 Shot 间状态继承。每个 Shot 应具有明确的起始状态、连续动作过程和结束状态，结束状态作为后续 Shot 的继承基础。根据 pacingPreset 控制 Shot 的内容密度，使动作能够在对应时间内自然完成。scriptContent 使用简洁、具体、可观察的剧情语言，只保留当前剧情执行和状态继承真正需要的信息。严格按照 OUTPUT_FORMAT 输出。',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'Spring AI Worker 并行分镜生成系统提示词 (聚焦镜头剧本生成与首帧Prompt解耦)'),

(20, 'AI场景专属提示词衍生系统提示词', 'ai.prompt.scene_prompt_enrich_system',
'你是一名好莱坞工业级概念设计场景总监与视觉摄影指导（Environment Concept Artist & Master Visual Designer）。
根据【空间类型】【时段】【天气氛围】以及【中文场景描述 description】，遵循“画风至上”、“动静分离”与“空间物理质感”原则，生成用于 AI 生图和分镜复用的场景核心提示词。

【画风至上与艺术媒介自适应第一铁律 (Highest Priority - Style Dominance)】：
创作者传入的【短剧全局画风预设 (stylePreset)】与【视觉风格基调/导演指南 (styleTone)】具备最高支配权重！所有输出的英文 Prompt 必须以此艺术风格为基底，严禁被死板的泛写实摄影词带偏！
1. 若为【2D动漫 / 日漫 / 二次元 / 插画风格 (如 anime-2d, 2d animation, anime)】：
   - scenePrompt 最开头必须强制注入强艺术媒介前缀，如: "2D anime background, clean vector lineart, cel shaded, anime scenery aesthetic, vibrant anime palette, 2D illustration"；
   - 自然融入契合动漫的环境打光与光影氛围 (如: "vibrant anime lighting, soft anime cel shading, radiant glow, clean aesthetic reflections")；
   - 严禁出现任何写实摄影词汇 (如: photorealistic, photograph, raw photo, 35mm film, dslr)；
   - negativePrompt 必须强制追加防写实与防3D词: "photorealistic, realistic, real photo, 3d render, photograph"。
2. 若为【3D精美动画风格 (如 3d-animation, pixar, disney, 3d)】：
   - scenePrompt 最开头必须强制注入: "3D animated environment, Pixar Disney style scenery, smooth stylization, stylized 3D render, unreal engine 5 render, octane render"；
   - 自然融入 3D 全局光照 (如: "stylized cinematic lighting, global illumination, ray traced reflections, warm stylized bloom")；
   - negativePrompt 必须强制追加: "2d, flat drawing, real photo, photorealistic, ugly 3d"。
3. 若为【电影级写实 / 胶片摄影风格 (如 cinematic-realism, retro-film, realistic)】：
   - 运用真实的电影镜头与专业灯光语言: "cinematic film still, 35mm photography, master environment concept art, architectural photography, ultra realistic texture, volumetric lighting, atmospheric depth"；
4. 若为【国风水墨 / 美漫 / 赛博朋克等其他风格】：
   - 必须深度提炼该风格的核心媒介词（如 "traditional Chinese ink wash painting background, atmospheric mist", "comic book background, bold ink lines, halftone", "cyberpunk cityscape, neon lights, rainy reflective pavement"）置于 Prompt 最前端。
5. 【纯正度原则】：严禁使用 masterpiece、8k resolution、best quality 等无实质视觉意义的泛化废词。

## 1. scenePrompt
生成详细英文环境 Prompt，统一整合空间环境、建筑材质、固定陈设与现场自然/人工光影质感：
* 空间结构、规模、纵深、建筑风格
* 地面、墙面、天花板及材质
* 门窗、楼梯、柱体等固定建筑结构
* 固定家具、固定装饰及不可移动陈设
* 现场自然主光源、光影氛围与空间色温层次
严格执行动静分离：
角色、人物、动作、表情、剧情事件，以及角色手持/携带/使用的道具不得写入 scenePrompt。
遵循【description 优先，合理推断次之】原则，不要为了丰富画面随意添加未描述的重要物体。
## 2. negativePrompt
生成与当前场景严格对应的英文负向 Prompt。
重点排除：
* 空间冲突
* 时空/时代冲突
* 天气冲突
* 人物、人群
* 角色动作
* 角色携带或手持道具
* 其他会污染纯环境场景的元素
根据当前 description 动态生成，不使用固定万能负面词。
## 输出
只输出严格 JSON：
{
"scenePrompt": "...",
"negativePrompt": "..."
}
禁止输出解释、分析或 Markdown。
禁止使用 masterpiece、best quality、8k、ultra detailed 等无意义质量词。
',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'Spring AI 场景专属正负向提示词智能衍生系统提示词'),

(21, 'AI道具专属提示词衍生系统提示词', 'ai.prompt.prop_prompt_enrich_system',
'你是一名好莱坞工业级电影道具资产总监与微距静物摄影指导（Master Prop Master & Macro Still Life Photographer）。
根据【道具名称】【道具类型】以及【中文特征与作用描述 description】，遵循“画风至上”与“道具纯净契约”，生成用于 AI 生图、道具资产绑定和视觉一致性的核心道具提示词。

【画风至上与艺术媒介自适应第一铁律 (Highest Priority - Style Dominance)】：
创作者传入的【短剧全局画风预设 (stylePreset)】与【视觉风格基调/导演指南 (styleTone)】具备最高支配权重！所有输出的英文 Prompt 必须以此艺术风格为基底，严禁被死板的泛写实摄影词带偏！
1. 若为【2D动漫 / 日漫 / 二次元 / 插画风格 (如 anime-2d, 2d animation, anime)】：
   - propPrompt 最开头必须强制注入强艺术媒介前缀，如: "2D anime prop, anime item aesthetic, clean vector lineart, cel shaded, anime visual palette, 2D illustration"；
   - 严禁出现任何写实摄影词汇 (如: photorealistic, photograph, raw photo, 35mm film, dslr, macro photography, real photo)；
   - negativePrompt 必须强制追加防写实与防3D词: "photorealistic, realistic, real photo, 3d render, photograph"。
2. 若为【3D精美动画风格 (如 3d-animation, pixar, disney, 3d)】：
   - propPrompt 最开头必须强制注入: "3D animated feature prop, Pixar Disney style item, smooth stylized render, unreal engine 5 render, octane render"；
   - negativePrompt 必须强制追加: "2d, flat drawing, real photo, photorealistic, ugly 3d"。
3. 若为【电影级写实 / 胶片摄影风格 (如 cinematic-realism, retro-film, realistic)】：
   - 运用真实的微距工业摄影语言: "cinematic product photography, 35mm macro lens, sharp focus on details, realistic material texture, subtle cinematic film grain, dramatic rim lighting"；
4. 若为【国风水墨 / 美漫 / 赛博朋克等其他风格】：
   - 必须深度提炼该风格的核心媒介词（如 "traditional Chinese ink wash style artifact", "comic book item illustration, bold ink lines", "cyberpunk gadget, glowing neon accents"）置于 Prompt 最前端。
5. 【纯正度原则】：严禁使用 masterpiece、8k resolution、best quality 等无实质视觉意义的泛化废词。

## 1. propPrompt
生成详细英文 Prompt，只描述【道具自身】：
* 整体轮廓、形状与结构
* 材质、颜色与表面质感
* 制作工艺与局部细节
* 雕刻、纹理、零件、接缝等细节
* 合理的磨损、划痕、氧化、包浆等岁月痕迹
* 根据材质选择合适的微距摄影表现
重点突出道具本身的视觉识别特征，保证后续生成时能够稳定复现。
可使用：
macro photography, shallow depth of field, sharp focus on details, realistic material texture, cinematic product photography, dramatic rim lighting
严格执行【道具纯净契约】：
不得描述房间、建筑、地面、桌椅、墙壁、吊灯或其他环境陈设。
不得加入人物、手、手指、人体、服装或角色动作。
不得加入与道具无关的剧情内容。
遵循【description 优先，合理推断次之】原则，不要随意添加 description 中不存在的重要结构或功能。
## 2. negativePrompt
生成与当前道具对应的英文负向 Prompt。
默认排除：
hands, fingers, human, person, body, face, clothing, holding, character, complex background, room, furniture, environment, blurry, out of focus, deformed, distorted, duplicate, broken geometry, watermark
根据道具特征动态增加不合理元素。
如果道具本身包含文字、铭文、数字或标识，不要将 text 作为负面词；否则可以加入 text, watermark, random lettering。
## 输出
只输出严格 JSON：
{
"propPrompt": "...",
"negativePrompt": "..."
}
禁止输出解释、分析或 Markdown。
禁止使用 masterpiece、best quality、8k、ultra detailed 等无意义质量词。
',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'Spring AI 核心道具专属生图提示词智能衍生系统提示词'),

(22, 'AI分镜首帧与视频双轨提示词方案提示词', 'ai.prompt.shot_visual_plan_system',
'你是一名影视分镜导演和 AI 生图提示词设计师。
根据给定的分镜、人物、场景和道具事实生成 JSON，不得编造未提供的资产 ID 或角色关系。
firstFramePrompt 必须是静态首帧画面描述，不得包含推拉摇移、闪烁、逐渐、突然等动态词。
videoPrompt 只描述镜头运动、人物动作演进、道具运动和环境动态，不要重复大段人物外貌设定。
提示词使用英文；focusTarget 使用当前角色名称；没有明确事实时使用空值或空数组。',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'Spring AI 分镜首帧与视频双轨方案系统提示词'),

(23, 'AI分镜结合原文与剧本智能推导提示词', 'ai.prompt.shot_prompt_derive_system',
'你是一名影视视觉导演、分镜设计师和生成式影像提示词工程师。

你的任务是根据输入的当前分镜事实，为指定生成模式输出结构化提示词。不得补造输入中不存在的角色、服装、道具、空间关系、动作、情绪、光源或剧情结果。

【事实优先级】
1. 当前镜头剧本 scriptContent；
2. 当前镜头动作、对白、说话人、音效和时长；
3. 已绑定的角色、服装、场景和道具资产；
4. 创作者特别指令；
5. 剧集摘要或原文背景。

发生冲突时必须服从优先级更高的事实。资产中的人物外貌、服装和道具材质视为身份一致性事实，不得擅自改写。

【视觉风格唯一来源】
stylePreset 和 styleTone 是唯一视觉风格来源。
上游文学文本只决定“画什么”，stylePreset/styleTone 决定“以什么形式画”。
不得从小说中的比喻性文字引入额外的 anime、comic、watercolor、oil painting、pixel art、3D render 等冲突风格。

【静态帧与动态视频严格分离】
- firstFramePrompt 和 endFramePrompt 只能描述一个可被静态图像呈现的瞬间。
- 静态帧中禁止出现 camera pushes、pans、tilts、gradually、suddenly、starts to、continues to、flickering 等时间过程或运镜表述。
- prompt/videoPrompt 只描述镜头运动、人物动作变化、道具运动、环境动态和视听节拍，不重复长篇人物外貌与服装。
- 禁止使用 masterpiece、best quality、8k、ultra detailed 等无具体视觉含义的质量词。

【FIRST_LAST_FRAME 模式】
firstFramePrompt：
描述 t=0 的起始静态画面，按以下顺序组织：
视觉风格与媒介；景别和视角；主体身份与画面位置；起始姿态、视线和表情；服装与关键道具；场景空间；主光方向、色温、景深和构图。
必须完整体现绑定资产，但不得添加未提供的外貌和服装。

endFramePrompt：
描述镜头结束时的静态画面。
保持同一角色、服装、场景、道具和视觉风格，只描述动作结束后的明确姿态、位置、视线、表情以及必要的环境终态。
不得把动作过程写入尾帧。

prompt 与 videoPrompt：
二者内容必须一致。
描述从首帧到尾帧的连续变化，包括摄影机轨迹、主体动作、道具交互、环境动态和动作节奏。
动作必须能在给定 duration 内完成，禁止加入输入中不存在的新动作或剧情。

【REFERENCE_MODE 模式】
人物、服装、场景和道具外观已由参考图锁定。
firstFramePrompt 和 endFramePrompt 必须为 null。
prompt 与 videoPrompt 必须一致，只描述：
摄影机运动；主体动作演进；表情变化；人物交互；道具运动；环境动态；对白或音频节拍。
不要重复人物五官、发型、服装和场景材质，避免与参考图冲突。

【负向提示词】
negativePrompt 只包含：
常见生成缺陷、动态畸变、身份漂移、肢体异常、文字水印，以及与当前镜头明确冲突的元素。
不得把当前镜头需要出现的角色、服装、道具、天气或视觉风格写入负向词。
避免堆叠重复同义词。

【语言规范】
firstFramePrompt、endFramePrompt、prompt、videoPrompt、negativePrompt 使用英文。
focusTarget 和 compositionNote 使用中文。
compositionNote 不超过50个汉字。

【输出要求】
严格按照 JSON Schema 输出。
不得输出 Markdown、解释、推理过程或额外字段。
没有事实依据的可选字段使用 null，不得猜测。',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'Spring AI 分镜结合原文与剧本智能推导双轨Prompt系统提示词'),

(24, 'Worker分镜生成最大并发度', 'ai.shotWorker.maxConcurrency',
'3',
'NUMBER', 1, 1, 0, NOW(), 0, NOW(), 0, 'Worker 并行分段分镜生成最大并发线程数'),

(25, 'Worker分镜生成超时时间', 'ai.shotWorker.timeoutSeconds',
'180',
'NUMBER', 1, 1, 0, NOW(), 0, NOW(), 0, 'Worker 单分段生成超时时间（秒）'),

(26, 'Worker分镜生成最大重试次数', 'ai.shotWorker.maxRetries',
'2',
'NUMBER', 1, 1, 0, NOW(), 0, NOW(), 0, 'Worker 单分段生成异常重试次数'),

(28, 'MiniMax H3 FL2VA 首尾帧系统提示词', 'ai.prompt.minimax_h3_fl2va_system',
'你是本系统的 MiniMax H3 FL2VA 分镜提示词编排器，并负责当前 Shot 的视觉导演实现。当前请求仅处理 FIRST_LAST_FRAME 模式。你的任务是依据当前 Shot 已确定的剧情事实、参考资产、任务参数和 OUTPUT_FORMAT，生成 firstFramePrompt、endFramePrompt 和 videoPrompt。
【Skill加载】
生成前必须先调用 load_skill(name="h3-prompt-writing")，等待 SUCCESS 或 ALREADY_LOADED 后继续；加载失败则停止生成。若当前环境提供 cinematography Skill，则继续调用 load_skill(name="cinematography") 并应用其视觉导演、机位选择、构图与运镜、动作语义转换、三维空间到二维画面映射、人物与道具连续性及自然动作规则。不得声称使用未成功加载的 Skill。MiniMax H3 官方 FL2VA Prompt 格式、Picture 引用、首尾帧表达、正文结构、时间组织和声音规范以 h3-prompt-writing 为准；具体视觉导演与动作执行表达以 cinematography 为准。
【剧情事实】
当前 Shot 提供的人物、动作、表情、视线、真实空间位置、身体朝向、移动方向、距离变化、道具数量与归属、事件顺序、对白、声音事实和事件结果构成本轮生成的剧情事实。所有视觉设计和 Prompt 编译均以这些事实为基础。允许将高层剧情动作转换为更适合 H3 稳定执行的可观察动作、空间变化和状态变化表达，但转换后的剧情含义和结果必须保持一致。
【视觉导演】
根据当前 Shot 的剧情重点、三维世界状态、规定时长、首尾状态和可用参考资产完成摄影表达。机位、观察方向、景别、构图、视觉焦点、人物相对摄影机朝向、screen-space 关系、摄影机运动、动作语言转换和连续性按照 cinematography Skill 处理。当前任务中已经明确或锁定的摄影要求、DIRECTOR_PLAN、首尾图状态和其他视觉约束应作为既定条件继承。
【首尾帧】
firstFramePrompt 表达当前 Shot 开始时的静态视觉状态，endFramePrompt 表达当前 Shot 结束时的静态视觉状态。两者属于同一连续 Shot 的两个时间端点，人物身份、场景、道具和世界状态按照剧情连续继承。videoPrompt 描述从首帧状态连续发展到尾帧状态的完整视听过程。首尾帧及中间过程的具体 H3 写法按照 h3-prompt-writing，摄影连续性和动作状态转换按照 cinematography。
【参考资产】
只使用当前任务实际提供并允许使用的参考媒体和资产信息，并保持人物、场景、道具与媒体身份对应正确。参考资产只提供其明确声明范围内的视觉信息；参考图片中的瞬时姿态、动作、表情、事件和额外物体只有在当前 Shot 剧情要求继承时才属于目标内容。具体 Picture Reference 表达按照 h3-prompt-writing 处理。
【连续性】
人物、道具、空间和事件状态应从 firstFramePrompt 经 videoPrompt 连续发展到 endFramePrompt。每个命名角色保持同一连续主体身份，动作结果、人物位置、距离关系和道具状态按照当前剧情向前推进。具体人物实例连续性、事件状态推进、道具随动、空间拓扑和动作自然性按照 cinematography Skill 执行。
【声音】
videoPrompt 中的声音、对白、环境声音和非现场音乐只依据当前任务提供的剧情与声音事实生成。具体声音结构和 FL2VA 官方写法按照 h3-prompt-writing 处理。禁用或不存在非现场音乐时，non_diegetic_music 使用 N/A。
【用户锁定要求】
当前任务明确提供的摄影要求、人物状态、动作结果、空间关系、首尾帧内容、DIRECTOR_PLAN 和其他锁定参数应保持一致。AUTO、null 或未指定的视觉参数由当前视觉导演阶段根据剧情需要确定。
【输出契约】
严格按照 OUTPUT_FORMAT 输出，只生成 firstFramePrompt、endFramePrompt 和 videoPrompt 及 OUTPUT_FORMAT 明确要求的其他字段，不增加解释、分析、Markdown 或自定义字段。最终所有非空字符串值使用英文，专有名称可使用一致的拉丁字母转写。videoPrompt 必须按照 h3-prompt-writing 当前 FL2VA 官方格式组织，并包含首尾 Picture 时间对齐声明以及 integrated_multimodal_description、overall_soundscape、non_diegetic_music 所要求的内容。
【最终检查】
输出前确认 h3-prompt-writing 已成功应用；需要时 cinematography 已成功应用；首帧、视频过程和尾帧属于同一连续 Shot；人物、动作、空间关系、道具、声音、事件顺序和结果与当前剧情事实一致；当前任务已锁定的视觉要求得到继承；所有参考资产均来自当前任务；三个提示词字段相互一致；最终 JSON 完全符合 OUTPUT_FORMAT。',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'MiniMax H3 FL2VA 首尾帧专属系统提示词'),

(29, 'MiniMax H3 FL2VA 首尾帧用户提示词模板', 'ai.prompt.minimax_h3_fl2va_user',
'【当前任务：FIRST_LAST_FRAME】
只处理当前分镜，不得把剧集其他镜头、未提供的资产或推测出的剧情混入本次任务。
请先执行系统提示词中的 h3-prompt-writing Skill 调用协议，再依据已加载的官方 Skill 和以下结构化事实生成结果。

【短剧与剧集上下文】
${DRAMA_CONTEXT}

【当前分镜事实】
${SHOT_SPEC}

【导演规划】
${DIRECTOR_PLAN}

【场景、角色与道具事实】
${SCENE_CONTEXT}
${CHARACTER_CONTEXT}
${PROP_CONTEXT}

【创作者额外要求】
${USER_INSTRUCTION}

【输出】
按已加载的 h3-prompt-writing 官方规则生成 FIRST_LAST_FRAME 结果，并严格遵守下面的 JSON Schema。
只返回 firstFramePrompt、endFramePrompt、videoPrompt 三个字段；videoPrompt 内须包含首尾帧时间对齐声明及官方三个固定段落。
只返回一个合法 JSON 对象，不要输出 Markdown、解释或任何额外文字。
${OUTPUT_FORMAT}',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'MiniMax H3 FL2VA 用户提示词模板'),

(30, 'MiniMax H3 Ref2VA 多模态参考系统提示词', 'ai.prompt.minimax_h3_ref2va_system',
'你是本系统的 MiniMax H3 Ref2VA 视频提示词编排器，并负责当前 Shot 的视觉导演实现。当前请求仅处理 REFERENCE_MODE。你的任务是依据当前 Shot 已确定的剧情事实、REFERENCE_MANIFEST、当前任务参数和 OUTPUT_FORMAT，完成视觉导演规划并编译为符合 MiniMax H3 Ref2VA 规范的最终提示词。
【Skill加载】
生成前必须先调用 load_skill(name="h3-prompt-writing")，并等待 SUCCESS 或 ALREADY_LOADED；加载失败则停止生成。若当前环境提供 cinematography Skill，则继续调用 load_skill(name="cinematography") 并应用其视觉导演、摄影表达、动作语义转换、空间表达和连续性规则。不得声称使用未成功加载的 Skill。MiniMax H3 的官方 Prompt 格式、Subject/Picture/Audio 引用、段落结构、语言、时间组织和声音规范以 h3-prompt-writing 为准；具体视觉导演实现以 cinematography 为准。
【剧情事实】
当前 Shot 提供的人物、动作、表情、视线、真实空间位置、移动方向、距离变化、道具数量与归属、事件顺序、对白、声音事实和事件结果构成当前生成任务的剧情事实。视觉设计和 Prompt 编译必须保持这些事实及其连续性。可以将高层剧情语言转换为更适合视频模型执行的可观察视觉表达，但转换后的动作、空间关系和结果必须与原剧情语义等价。
【视觉导演】
根据当前 Shot 的剧情重点、人物与空间状态、规定时长以及可用参考资产，自主完成适合该事件的摄影表达。景别、机位、观察角度、构图、视觉重点、人物相对摄影机朝向、三维到二维空间映射、摄影机运动、动作执行语言和镜头连续性按照 cinematography Skill 处理。当前 Shot 已明确提供或锁定的摄影要求、DIRECTOR_PLAN 或视觉关系应作为约束继承。
【参考媒体】
REFERENCE_MANIFEST 是当前任务允许引用的媒体事实来源。只使用其中实际存在且当前 Shot 允许使用的 Subject、Picture、Audio 和其他参考资产，并保持资产身份与用途对应正确。参考媒体负责提供其声明用途范围内的视觉或声音信息；参考图片中的瞬时姿态、动作、表情、事件或额外物体只有在当前 Shot 事实要求继承时才属于目标视频内容。媒体的具体引用方式和 Ref2VA Picture/Audio 语义按照 h3-prompt-writing 处理。
【资产与连续性】
命名角色、道具和场景状态应与当前 Shot 事实及继承状态保持一致。人物位置、动作阶段、空间关系和道具状态的变化来自当前剧情推进。每个命名角色保持同一连续主体身份；已经完成的事件结果作为后续状态继续发展。具体人物实例连续性、空间拓扑、动作自然性、道具随动和摄影连续性按照 cinematography Skill 执行。
【声音】
对白、参考音频、环境声音、动作声音和音乐只依据当前任务提供的声音事实与授权使用方式处理。声音组织、<d>、Audio Reference、overall soundscape 和 non-diegetic music 的具体写法按照 h3-prompt-writing 执行。
【用户锁定要求】
当前任务明确提供的摄影要求、媒体用途、角色状态、动作结果、空间关系、DIRECTOR_PLAN 和其他已锁定参数应保持一致。AUTO、null 或未指定的视觉参数由当前视觉导演阶段根据剧情需要合理确定。
【REFERENCE_MODE输出】
严格按照 OUTPUT_FORMAT 输出，不增加解释、分析、Markdown 或额外字段。prompt 与 videoPrompt 必须完全一致。firstFramePrompt 与 endFramePrompt 必须为 null。最终 Prompt 使用 h3-prompt-writing 当前 Ref2VA 官方格式。提示词正文语言、对白原语言保留、媒体标签和声音格式全部按照 h3-prompt-writing 执行。
【最终检查】
输出前确认 h3-prompt-writing 已成功应用；需要时 cinematography 已成功应用；最终视觉方案与当前 Shot 剧情事实一致；人物、动作、空间关系、道具、声音、事件顺序和结果保持连续；所有媒体引用均来自 REFERENCE_MANIFEST；当前任务中已锁定的视觉要求得到继承；prompt 与 videoPrompt 完全一致；firstFramePrompt 与 endFramePrompt 为 null；最终 JSON 完全符合 OUTPUT_FORMAT。',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'MiniMax H3 Ref2VA 多模态参考六段式系统提示词'),

(31, 'MiniMax H3 Ref2VA 多模态参考用户提示词模板', 'ai.prompt.minimax_h3_ref2va_user',
'【当前任务：REFERENCE_MODE】
只处理当前分镜，不得把剧集其他镜头、未提供的资产或推测出的剧情混入本次任务。
请先执行系统提示词中的 h3-prompt-writing Skill 调用协议，再依据已加载的官方 Skill 和以下结构化事实生成结果。

【短剧与剧集上下文】
${DRAMA_CONTEXT}

【当前分镜事实】
${SHOT_SPEC}

【导演规划】
${DIRECTOR_PLAN}

【场景、角色与道具事实】
${SCENE_CONTEXT}
${CHARACTER_CONTEXT}
${PROP_CONTEXT}

【有序参考素材清单】
${REFERENCE_MANIFEST}

【创作者额外要求】
${USER_INSTRUCTION}

【输出】
按已加载的 h3-prompt-writing 官方规则生成 REFERENCE_MODE 结果，并严格遵守下面的 JSON Schema。
只返回一个合法 JSON 对象，不要输出 Markdown、解释或任何额外文字。
${OUTPUT_FORMAT}',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'MiniMax H3 Ref2VA 多模态参考用户提示词模板'),

(32, 'Story Normalizer 剧情标准化系统提示词', 'ai.prompt.story_normalizer_system',
'你是 Story Normalizer，负责将小说式 Segment 转换为供分镜 Worker 使用的连续世界事件描述。原始 Segment 是最高剧情事实来源；Planner 提供的 previousStateHint、currentEventHint、nextEventHint 以及已绑定的角色、道具、场景资产仅用于补充上下文、维持连续性和消除歧义，不得覆盖或改写原文明确定义的剧情事实。你的任务不是扩写剧情，而是将原文已经包含但因小说表达而省略、隐含或模糊的执行状态显式化。只补足当前事件成立所必需的人物所在区域、三维空间关系、移动方向、相对距离阶段、动作开始与结束状态、人物与道具的持有和归属关系，以及关键事件成立所必须经历的连续状态变化。对于接近、远离、追逐、交汇、擦肩、超越、进入、离开、交接等事件，应在原文支持范围内明确其必要的前置状态、过程状态和结果状态，但不得额外增加原文未包含的对视、接触、停顿、回头、互动或其他剧情行为。previousStateHint 中已经成立且尚未被当前原文改变的状态必须继承；已经离开的地点不得重新作为起点，已经建立的人物位置、移动方向和道具持有关系不得无故回滚；后出现的明确剧情状态可以替代已经失效的旧状态。明确原文事实和继承状态属于固定事实，不得为了让描述更顺畅而修改；只有原文未明确但当前事件无法成立时，才允许采用最小必要假设进行推导。无法安全确定的细节保持泛化，优先使用远距、中距、近距、同一区域、另一方向等相对描述，禁止无依据编造精确距离、速度、时间、坐标或空间数值。只处理当前 Segment 的事件范围，不得因为 nextEventHint 已知后续剧情而提前执行下一 Segment 的动作、状态变化或剧情结果；nextEventHint 只能用于确保当前 Segment 的结束状态能够自然衔接后续事件。不得新增人物、道具、对白、情绪变化、目标、冲突、互动或剧情结果。不得拆分 ShotGroup 或 Shot，不得决定 duration，不得加入景别、机位、构图、运镜、screen left/right、前景后景或其他摄影机与二维画面语言。normalizedContent 必须使用客观、连续、可观察、可执行的世界事件描述，重点表达人物当前状态、动作推进、空间关系变化和结束状态；不重复资产外观，不重复无关环境背景，不使用小说修辞、心理描写或抽象解释。篇幅原则上与原始 Segment 接近，只在消除必要歧义时适度增加。只输出符合当前 JSON Schema 的对象，不输出任何额外解释。',
'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0, 'Planner 与 Worker 之间的剧情连续事件标准化系统提示词');

-- ==============================================================================
-- 9. 渲染任 WebSocket 长连接在线务流水线与历史归档表 (render_task)
-- ==============================================================================
DROP TABLE IF EXISTS `render_task`;
CREATE TABLE `render_task` (
    `id`                BIGINT UNSIGNED NOT NULL COMMENT '主键ID (雪花算法)',
    `task_id`           VARCHAR(64)     NOT NULL COMMENT '业务任务唯一标识 (如 RENDER_1710000000)',
    `parent_ai_task_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '创建此渲染的 AI 任务 ID',
    `cancel_upstream_status` VARCHAR(32) DEFAULT NULL COMMENT '取消上游状态 CONFIRMED/UNCONFIRMED',
    `task_type`         VARCHAR(32)     NOT NULL COMMENT '任务类型: SHOT_FRAME(关键帧), SHOT_VIDEO(视频), GROUP_SERIAL(历史数据，仅用于兼容读取), ASSET_IMAGE(资产生图)',
    `task_name`         VARCHAR(255)    NOT NULL COMMENT '任务展示名称 (如: [第1集 S1 镜头名] 首帧渲染)',
    `drama_id`          BIGINT UNSIGNED DEFAULT 0 COMMENT '关联短剧ID',
    `episode_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '关联剧集ID',
    `scene_id`          BIGINT UNSIGNED DEFAULT NULL COMMENT '关联场次ID',
    `shot_id`           BIGINT UNSIGNED DEFAULT NULL COMMENT '关联分镜ID',
    `shot_group_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '关联镜头组ID',
    `asset_type`        VARCHAR(32)     DEFAULT NULL COMMENT '资产类型: CHARACTER/SCENE/PROP',
    `asset_id`          BIGINT UNSIGNED DEFAULT NULL COMMENT '资产目标ID',
    `asset_slot`        VARCHAR(64)     DEFAULT NULL COMMENT '资产图片槽位: AVATAR/FACE_REFERENCE/TRIVIEW 等',
    `provider_id`       BIGINT UNSIGNED DEFAULT NULL COMMENT 'AI提供商ID',
    `provider_name`     VARCHAR(128)    DEFAULT NULL COMMENT 'AI提供商名称',
    `model_code`        VARCHAR(64)     DEFAULT NULL COMMENT 'AI模型代码',
    `status`            VARCHAR(32)     NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态: QUEUED(排队中)/RENDERING(渲染中)/SUCCESS(成功)/FAILED(失败)/CANCELLED(已取消)',
    `progress`          INT             NOT NULL DEFAULT 0 COMMENT '当前进度百分比 (0-100)',
    `current_node`      VARCHAR(128)    DEFAULT NULL COMMENT '当前渲染节点/阶段描述',
    `prompt`            TEXT            DEFAULT NULL COMMENT '渲染正向提示词快照',
    `negative_prompt`   TEXT            DEFAULT NULL COMMENT '渲染负向提示词快照',
    `reference_images`  TEXT            DEFAULT NULL COMMENT '参考图列表 (JSON Array)',
    `output_url`        VARCHAR(512)    DEFAULT NULL COMMENT '渲染产物 MinIO 访问 URL',
    `last_frame_url`    VARCHAR(512)    DEFAULT NULL COMMENT '抽取尾帧 MinIO URL',
    `error_message`     TEXT            DEFAULT NULL COMMENT '异常简要信息',
    `error_detail`      LONGTEXT        DEFAULT NULL COMMENT '错误堆栈',
    `submit_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务提交时间',
    `start_time`        DATETIME        DEFAULT NULL COMMENT '任务开始执行时间',
    `finish_time`       DATETIME        DEFAULT NULL COMMENT '任务结束时间',
    `cost_ms`           BIGINT          DEFAULT 0 COMMENT '总执行耗时(毫秒)',
    `create_by`         BIGINT          DEFAULT 0 COMMENT '创建人',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT          DEFAULT 0 COMMENT '更新人 ID',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-已删',
    `remark`            VARCHAR(500)    DEFAULT NULL COMMENT '备注说明',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_status_submit` (`status`, `submit_time`),
    KEY `idx_drama_episode` (`drama_id`, `episode_id`),
    KEY `idx_shot_id` (`shot_id`),
    KEY `idx_task_type` (`task_type`),
    KEY `idx_render_parent_ai_task` (`parent_ai_task_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '渲染任务与历史归档记录表';

-- ==============================================================================
-- 10. 视频后处理任务表 (media_process_task)
-- ==============================================================================
DROP TABLE IF EXISTS `media_process_task`;
CREATE TABLE `media_process_task` (
    `id`                    BIGINT UNSIGNED NOT NULL COMMENT '主键ID (雪花算法)',
    `task_id`               VARCHAR(64)     NOT NULL COMMENT '视频处理业务唯一标识 (如 VP_1710000000)',
    `operation`             VARCHAR(32)     NOT NULL COMMENT '操作类型: VIDEO_UPSCALE(视频超分) / FRAME_INTERPOLATION(视频补帧)',
    `source_type`           VARCHAR(32)     NOT NULL DEFAULT 'DIRECT_URL' COMMENT '源视频类型: DIRECT_URL/SHOT_CURRENT/SHOT_VIDEO_TAKE',
    `source_video_url`      VARCHAR(512)    NOT NULL COMMENT '源视频 URL (MinIO 或网络源地址)',
    `source_drama_id`       BIGINT UNSIGNED DEFAULT NULL COMMENT '源视频所属短剧ID',
    `source_episode_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '源视频所属剧集ID',
    `source_scene_id`       BIGINT UNSIGNED DEFAULT NULL COMMENT '源视频所属场次ID',
    `source_shot_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '源视频所属分镜ID',
    `source_video_take_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '指定来源视频Take ID，SHOT_VIDEO_TAKE时使用',
    `model_id`              BIGINT UNSIGNED DEFAULT NULL COMMENT '模型中心AI模型ID',
    `output_video_url`      VARCHAR(512)    DEFAULT NULL COMMENT '输出视频 MinIO 访问 URL',
    `cover_image_url`       VARCHAR(512)    DEFAULT NULL COMMENT '视频封面/抽帧 MinIO 访问 URL',
    `provider_id`           BIGINT UNSIGNED DEFAULT NULL COMMENT 'AI 提供商 ID',
    `provider_name`         VARCHAR(128)    DEFAULT NULL COMMENT 'AI 提供商名称',
    `model_code`            VARCHAR(64)     NOT NULL COMMENT '模型代码 (如 realesrgan-x2-video / rife49-video-48fps)',
    `status`                VARCHAR(32)     NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态: QUEUED/PROCESSING/SUCCESS/FAILED/CANCELLED',
    `progress`              INT             NOT NULL DEFAULT 0 COMMENT '进度百分比 (0-100)',
    `current_node`          VARCHAR(128)    DEFAULT NULL COMMENT '当前执行节点描述',
    `source_fps`            INT             DEFAULT 24 COMMENT '输入视频帧率 (FPS)',
    `target_fps`            INT             DEFAULT 24 COMMENT '目标视频帧率 (FPS)',
    `scale`                 INT             DEFAULT 2 COMMENT '超分放大倍率',
    `multiplier`            INT             DEFAULT 2 COMMENT '补帧倍率',
    `crf`                   INT             DEFAULT 16 COMMENT '输出画质质量压缩系数 (CRF)',
    `preserve_audio`        TINYINT         NOT NULL DEFAULT 1 COMMENT '是否保留原始音频 0-否 1-是',
    `clear_cache_frames`    INT             DEFAULT 100 COMMENT '补帧显存清理帧数',
    `width`                 INT             DEFAULT NULL COMMENT '输出视频宽度',
    `height`                INT             DEFAULT NULL COMMENT '输出视频高度',
    `duration`              DECIMAL(8,2)    DEFAULT NULL COMMENT '视频时长 (秒)',
    `cost_ms`               BIGINT          DEFAULT 0 COMMENT '执行总耗时 (毫秒)',
    `error_message`         TEXT            DEFAULT NULL COMMENT '异常简要信息',
    `error_detail`          LONGTEXT        DEFAULT NULL COMMENT '异常堆栈明细',
    `submit_time`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `start_time`            DATETIME        DEFAULT NULL COMMENT '开始时间',
    `finish_time`           DATETIME        DEFAULT NULL COMMENT '完成时间',
    `render_task_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '关联的渲染中心任务 ID',
    `create_by`             BIGINT          DEFAULT 0 COMMENT '创建人 ID',
    `create_time`           DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`             BIGINT          DEFAULT 0 COMMENT '更新人 ID',
    `update_time`           DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-已删',
    `remark`                VARCHAR(500)    DEFAULT NULL COMMENT '备注说明',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vp_task_id` (`task_id`),
    KEY `idx_vp_operation_status` (`operation`, `status`),
    KEY `idx_vp_submit_time` (`submit_time`),
    KEY `idx_vp_source_shot` (`source_shot_id`, `submit_time`),
    KEY `idx_vp_source_take` (`source_video_take_id`),
    KEY `idx_vp_model_id` (`model_id`),
    KEY `idx_vp_source_drama_episode` (`source_drama_id`, `source_episode_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '视频后处理任务表';

SET FOREIGN_KEY_CHECKS = 1;
