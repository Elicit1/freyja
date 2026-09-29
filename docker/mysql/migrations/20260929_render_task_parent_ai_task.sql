-- Persist the AI task that started a render so cancellation still finds its remote job.
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'render_task' AND COLUMN_NAME = 'parent_ai_task_id'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `render_task` ADD COLUMN `parent_ai_task_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''创建此渲染的 AI 任务 ID'' AFTER `task_id`',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'render_task' AND COLUMN_NAME = 'cancel_upstream_status'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `render_task` ADD COLUMN `cancel_upstream_status` VARCHAR(32) DEFAULT NULL COMMENT ''取消上游状态 CONFIRMED/UNCONFIRMED'' AFTER `parent_ai_task_id`',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'render_task' AND INDEX_NAME = 'idx_render_parent_ai_task'
);
SET @ddl = IF(@index_exists = 0,
    'ALTER TABLE `render_task` ADD INDEX `idx_render_parent_ai_task` (`parent_ai_task_id`, `status`)',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;
