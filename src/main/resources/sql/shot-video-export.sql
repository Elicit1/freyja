CREATE TABLE IF NOT EXISTS shot_video_export_task (
 id BIGINT NOT NULL PRIMARY KEY,
 drama_id BIGINT NOT NULL,
 drama_title VARCHAR(255) NOT NULL,
 name VARCHAR(200) NOT NULL,
 request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 retry_of_task_id BIGINT NULL,
 concat_videos TINYINT(1) NOT NULL DEFAULT 0,
 status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
 attempt_token VARCHAR(40) NULL,
 revision INT NOT NULL DEFAULT 1,
 total INT NOT NULL DEFAULT 0,
 processed INT NOT NULL DEFAULT 0,
 succeeded INT NOT NULL DEFAULT 0,
 failed INT NOT NULL DEFAULT 0,
 skipped INT NOT NULL DEFAULT 0,
 progress INT NOT NULL DEFAULT 0,
 bytes_read BIGINT NOT NULL DEFAULT 0,
 current_stage VARCHAR(512) NULL,
 error_message TEXT NULL,
 filename VARCHAR(255) NULL,
 object_key VARCHAR(512) NULL,
 size BIGINT NULL,
 created_at DATETIME(3) NOT NULL,
 started_at DATETIME(3) NULL,
 finished_at DATETIME(3) NULL,
 expires_at DATETIME(3) NULL,
 UNIQUE KEY uk_video_export_request (request_id),
 KEY idx_video_export_queue (status,id),
 KEY idx_video_export_drama (drama_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS shot_video_export_item (
 id BIGINT NOT NULL PRIMARY KEY,
 task_id BIGINT NOT NULL,
 shot_id BIGINT NOT NULL,
 execution_order INT NOT NULL,
 zip_entry_path VARCHAR(512) NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 status VARCHAR(32) NOT NULL,
 error_message TEXT NULL,
 size BIGINT NULL,
 checksum VARCHAR(64) NULL,
 UNIQUE KEY uk_video_export_shot (task_id,shot_id),
 UNIQUE KEY uk_video_export_path (task_id,zip_entry_path),
 KEY idx_video_export_items (task_id,execution_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS shot_video_export_dispatch (
 id INT NOT NULL PRIMARY KEY,
 task_id BIGINT NULL,
 attempt_token VARCHAR(40) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO shot_video_export_dispatch(id) VALUES (1);
