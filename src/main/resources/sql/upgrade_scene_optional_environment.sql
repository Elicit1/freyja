-- 可重复执行。只修正列约束和默认值，保留所有已保存的创作内容。
ALTER TABLE res_scene
    MODIFY COLUMN scene_type VARCHAR(32) NULL DEFAULT NULL COMMENT '空间类型 INDOOR/OUTDOOR/STUDIO/VIRTUAL，未指定时为空',
    MODIFY COLUMN time_of_day VARCHAR(32) NULL DEFAULT NULL COMMENT '时段 DAY/NIGHT/SUNSET/DAWN，未指定时为空';
ALTER TABLE drama_scene
    MODIFY COLUMN scene_type VARCHAR(32) NULL DEFAULT NULL COMMENT '空间类型 INDOOR/OUTDOOR/STUDIO/VIRTUAL，未指定时为空',
    MODIFY COLUMN time_of_day VARCHAR(32) NULL DEFAULT NULL COMMENT '时段 DAY/NIGHT/SUNSET/DAWN，未指定时为空';
-- 老库仍可能遗留景别/运镜非必填改动之前的默认值。
ALTER TABLE drama_shot
    MODIFY COLUMN shot_type VARCHAR(32) NULL DEFAULT NULL COMMENT '景别 (字典 shot_type)，未指定时为空',
    MODIFY COLUMN camera_movement VARCHAR(32) NULL DEFAULT NULL COMMENT '运镜方式 (字典 camera_movement)，未指定时为空';
