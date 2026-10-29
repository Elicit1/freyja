-- Additive, repeatable upgrade for durable video execution stages.

SET @stage_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='video_render_batch_item' AND column_name='execution_stage'), 'SELECT 1', 'ALTER TABLE video_render_batch_item ADD COLUMN execution_stage VARCHAR(32) NULL');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='video_render_batch_item' AND column_name='stage_message'), 'SELECT 1', 'ALTER TABLE video_render_batch_item ADD COLUMN stage_message VARCHAR(255) NULL');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='video_render_batch_item' AND column_name='stage_updated_at'), 'SELECT 1', 'ALTER TABLE video_render_batch_item ADD COLUMN stage_updated_at DATETIME(3) NULL');
PREPARE stage_stmt FROM @stage_ddl;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;
