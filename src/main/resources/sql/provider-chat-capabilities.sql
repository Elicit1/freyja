-- Additive, repeatable upgrade for existing databases. Does not alter model or run snapshots.
SET NAMES utf8mb4;
SET @freyja_chat_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_provider' AND column_name = 'chat_capabilities_json'
);
SET @freyja_chat_upgrade = IF(@freyja_chat_column_exists = 0,
    'ALTER TABLE ai_provider ADD COLUMN chat_capabilities_json TEXT NULL COMMENT ''CHAT默认能力，模型逐字段覆盖'' AFTER base_url',
    'SELECT ''chat_capabilities_json already exists'' AS migration_status');
PREPARE freyja_chat_upgrade_statement FROM @freyja_chat_upgrade;
EXECUTE freyja_chat_upgrade_statement;
DEALLOCATE PREPARE freyja_chat_upgrade_statement;
