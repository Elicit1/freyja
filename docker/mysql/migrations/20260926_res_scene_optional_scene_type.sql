-- 已有数据库只需执行一次；保留现有场景的空间类型。
ALTER TABLE `res_scene`
    MODIFY COLUMN `scene_type` VARCHAR(32) NULL DEFAULT NULL
    COMMENT '空间类型 INDOOR(室内)/OUTDOOR(室外)/STUDIO(影棚)/VIRTUAL(虚构)，未指定时为空';
