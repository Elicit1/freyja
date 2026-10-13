-- 增量初始化：不删除业务数据、Agent会话或已有MCP配置。
CREATE TABLE IF NOT EXISTS ai_capability_catalog_state (
    id BIGINT PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0,
    active_release_id BIGINT NULL, payload_json LONGTEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_capability_definition (
    id BIGINT PRIMARY KEY, handler_key VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
    display_name VARCHAR(160) NOT NULL, UNIQUE KEY uk_handler(handler_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_capability_version (
    id BIGINT PRIMARY KEY, capability_id BIGINT NOT NULL, version_no BIGINT NOT NULL,
    contract_json LONGTEXT NOT NULL, KEY idx_capability(capability_id,version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_capability_binding (
    id BIGINT PRIMARY KEY, capability_id BIGINT NOT NULL,
    consumer_type VARCHAR(32) NOT NULL, tool_name VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    binding_json LONGTEXT NOT NULL, KEY idx_consumer(consumer_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_capability_rule (
    rule_code VARCHAR(64) PRIMARY KEY, content LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_capability_release (
    id BIGINT PRIMARY KEY, revision BIGINT NOT NULL,
    payload_json LONGTEXT NOT NULL, content_hash CHAR(64) NOT NULL,
    note VARCHAR(1000) NULL, create_time DATETIME NOT NULL,
    UNIQUE KEY uk_release_revision(revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_capability_outbox (
    id BIGINT PRIMARY KEY, revision BIGINT NOT NULL, active_release_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NOT NULL, KEY idx_pending(status,next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
