-- Private administrator credentials, separate from the editable/exportable sys_config table.
CREATE TABLE IF NOT EXISTS sys_admin_credential (
    username VARCHAR(64) NOT NULL PRIMARY KEY,
    password_hash VARCHAR(512) NOT NULL,
    session_version VARCHAR(64) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
