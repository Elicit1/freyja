-- Apply once before deploying batch rendering. No existing business rows are rewritten.
CREATE TABLE IF NOT EXISTS video_render_batch (
 id BIGINT PRIMARY KEY, drama_id BIGINT NOT NULL, name VARCHAR(200) NOT NULL,
 request_id VARCHAR(80) NOT NULL, request_hash VARCHAR(64) NOT NULL, options_json LONGTEXT NOT NULL,
 status VARCHAR(32) NOT NULL, cancel_requested TINYINT NOT NULL DEFAULT 0, revision INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL, finished_at DATETIME(3),
 UNIQUE KEY uk_video_batch_request(request_id), KEY ix_video_batch_status(status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS video_render_batch_item (
 id BIGINT PRIMARY KEY, batch_id BIGINT NULL, queue_order BIGINT NOT NULL, shot_id BIGINT NOT NULL,
 task_id VARCHAR(100) NOT NULL, name VARCHAR(255) NOT NULL, phase VARCHAR(20) NOT NULL,
 execution_order INT NOT NULL, status VARCHAR(32) NOT NULL,
 snapshot_json LONGTEXT NOT NULL, resolved_json LONGTEXT NULL, error_message TEXT NULL,
 output_url TEXT NULL, upstream_status VARCHAR(32) NULL, failure_phase VARCHAR(32) NULL, recovery_url TEXT NULL,
 created_at DATETIME(3) NOT NULL, finished_at DATETIME(3),
 UNIQUE KEY uk_video_item_task(task_id), UNIQUE KEY uk_video_batch_shot(batch_id,shot_id),
 KEY ix_video_queue(status,queue_order,execution_order), KEY ix_video_items(batch_id,execution_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shot_video_render_slot (
 shot_id BIGINT PRIMARY KEY, task_id VARCHAR(100) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS video_render_dispatch_state (
 id BIGINT PRIMARY KEY, task_id VARCHAR(100) NULL, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO video_render_dispatch_state(id) VALUES (1);
