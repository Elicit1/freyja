-- Apply once before deploying reference-video generation. No existing model is enabled automatically.
ALTER TABLE drama_shot ADD COLUMN ref_videos_json LONGTEXT NULL COMMENT 'Creator-authored video reference selection';
ALTER TABLE drama_shot_video_take ADD COLUMN ref_videos_json LONGTEXT NULL COMMENT 'Immutable prepared video reference snapshot';
ALTER TABLE render_task ADD COLUMN request_snapshot_json LONGTEXT NULL COMMENT 'Versioned render input snapshot, including failed tasks';

CREATE TABLE IF NOT EXISTS shot_video_reference_preflight (
 preflight_id VARCHAR(64) PRIMARY KEY,
 shot_id BIGINT NOT NULL, provider_id BIGINT NOT NULL, model_code VARCHAR(100) NOT NULL,
 input_fingerprint VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,
 data_json LONGTEXT NOT NULL, created_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL,
 KEY ix_video_reference_preflight(shot_id,provider_id,model_code,input_fingerprint,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
