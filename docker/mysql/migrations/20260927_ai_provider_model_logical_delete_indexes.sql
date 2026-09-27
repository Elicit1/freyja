-- Run once (safe to rerun) against an existing freyja database.
-- Soft-deleted providers/models retain their codes, so a unique index on those
-- codes prevents creating a new active record with the same code.
DROP PROCEDURE IF EXISTS migrate_ai_code_index;
DELIMITER //

CREATE PROCEDURE migrate_ai_code_index(
    IN table_name_param VARCHAR(64),
    IN code_column_param VARCHAR(64),
    IN old_index_param VARCHAR(64),
    IN new_index_param VARCHAR(64),
    IN new_columns_param VARCHAR(128)
)
BEGIN
    DECLARE unique_index_names LONGTEXT;
    DECLARE index_position INT DEFAULT 0;
    DECLARE unique_index_name VARCHAR(64);
    DECLARE existing_index_count INT DEFAULT 0;
    SELECT COALESCE(JSON_ARRAYAGG(INDEX_NAME), JSON_ARRAY()) INTO unique_index_names
    FROM (
        SELECT DISTINCT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = table_name_param
          AND COLUMN_NAME = code_column_param
          AND NON_UNIQUE = 0
          AND INDEX_NAME <> 'PRIMARY'
    ) AS unique_indexes;

    WHILE index_position < JSON_LENGTH(unique_index_names) DO
        SET unique_index_name = JSON_UNQUOTE(JSON_EXTRACT(
            unique_index_names, CONCAT('$[', index_position, ']')));
        SET @ai_index_ddl = CONCAT('ALTER TABLE `', table_name_param,
            '` DROP INDEX `', REPLACE(unique_index_name, '`', '``'), '`');
        PREPARE ai_index_statement FROM @ai_index_ddl;
        EXECUTE ai_index_statement;
        DEALLOCATE PREPARE ai_index_statement;
        SET index_position = index_position + 1;
    END WHILE;

    SELECT COUNT(*) INTO existing_index_count
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = table_name_param
      AND INDEX_NAME = old_index_param;
    IF existing_index_count > 0 THEN
        SET @ai_index_ddl = CONCAT('ALTER TABLE `', table_name_param,
            '` DROP INDEX `', old_index_param, '`');
        PREPARE ai_index_statement FROM @ai_index_ddl;
        EXECUTE ai_index_statement;
        DEALLOCATE PREPARE ai_index_statement;
    END IF;

    SELECT COUNT(*) INTO existing_index_count
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = table_name_param
      AND INDEX_NAME = new_index_param;
    IF existing_index_count = 0 THEN
        SET @ai_index_ddl = CONCAT('ALTER TABLE `', table_name_param,
            '` ADD INDEX `', new_index_param, '` (', new_columns_param, ')');
        PREPARE ai_index_statement FROM @ai_index_ddl;
        EXECUTE ai_index_statement;
        DEALLOCATE PREPARE ai_index_statement;
    END IF;
END//

DELIMITER ;

CALL migrate_ai_code_index('ai_provider', 'provider_code',
    'idx_provider_code', 'idx_provider_code_deleted', '`provider_code`, `deleted`');
CALL migrate_ai_code_index('ai_model', 'model_code',
    'idx_provider_model', 'idx_provider_model_deleted', '`provider_id`, `model_code`, `deleted`');

DROP PROCEDURE migrate_ai_code_index;
