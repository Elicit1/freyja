-- Additive MCP business journal. Apply before enabling FREYJA_MCP_WRITE_ENABLED.
-- Internal Agent, model, Prompt and Skill tables are unchanged.
CREATE TABLE IF NOT EXISTS mcp_business_operation (
    id BIGINT NOT NULL PRIMARY KEY,
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool_name VARCHAR(80) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    drama_id BIGINT NULL,
    created_project TINYINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    result_json LONGTEXT NULL,
    create_time DATETIME NOT NULL,
    complete_time DATETIME NULL,
    UNIQUE KEY uk_mcp_business_request (request_id),
    KEY idx_mcp_created_project (drama_id, created_project, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
