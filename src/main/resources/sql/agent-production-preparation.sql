-- Additive preparation schema. Apply this file only to existing deployments.
SET NAMES utf8mb4;
ALTER TABLE `res_keyframe` MODIFY COLUMN `frame_url` VARCHAR(512) NULL COMMENT '关键图图片URL，可先保存文字草稿';

CREATE TABLE IF NOT EXISTS `agent_preparation_job` (
 `id` BIGINT NOT NULL,
 `create_by` BIGINT DEFAULT 0,
 `create_time` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
 `update_by` BIGINT DEFAULT 0,
 `update_time` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 `deleted` TINYINT NOT NULL DEFAULT 0,
 `remark` VARCHAR(500),
 `run_id` BIGINT NOT NULL,
 `session_id` BIGINT NOT NULL,
 `drama_id` BIGINT,
 `decomposition_task_id` BIGINT,
 `status` VARCHAR(32) NOT NULL,
 `source_text` MEDIUMTEXT,
 `request_json` JSON NOT NULL,
 `shot_ids_json` JSON NOT NULL,
 `assets_json` JSON NOT NULL,
 `report_json` JSON,
 UNIQUE KEY uk_preparation_run (run_id, deleted),
 KEY idx_preparation_session (session_id),
 PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `shot_production_plan` (
 `id` BIGINT NOT NULL,
 `create_by` BIGINT DEFAULT 0,
 `create_time` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
 `update_by` BIGINT DEFAULT 0,
 `update_time` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 `deleted` TINYINT NOT NULL DEFAULT 0,
 `remark` VARCHAR(500),
 `drama_id` BIGINT NOT NULL,
 `shot_id` BIGINT NOT NULL,
 `run_id` BIGINT NOT NULL,
 `revision` INT NOT NULL,
 `plan_json` JSON NOT NULL,
 `input_fingerprint` VARCHAR(64),
 `applied_fingerprint` VARCHAR(64),
 `shot_state_fingerprint` VARCHAR(64),
 `prompt_fingerprint` VARCHAR(64),
 `prompt_task_id` BIGINT,
 `prompt_task_fingerprint` VARCHAR(64),
 `prompt_task_revision` INT,
 `locked_fields_json` JSON NOT NULL,
 `preparation_status` VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_production_shot (shot_id, deleted),
 KEY idx_production_drama (drama_id),
 PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `agent_material_requirement` (
 `id` BIGINT NOT NULL,
 `create_by` BIGINT DEFAULT 0,
 `create_time` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
 `update_by` BIGINT DEFAULT 0,
 `update_time` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 `deleted` TINYINT NOT NULL DEFAULT 0,
 `remark` VARCHAR(500),
 `drama_id` BIGINT NOT NULL,
 `material_key` VARCHAR(180) NOT NULL,
 `source_type` VARCHAR(32) NOT NULL,
 `source_id` BIGINT NOT NULL,
 `name` VARCHAR(200) NOT NULL,
 `description` MEDIUMTEXT,
 `status` VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_material_identity (drama_id, material_key, deleted),
 PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
