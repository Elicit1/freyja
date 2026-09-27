-- Existing databases: add the shot seed and initialize historical shots.
-- New shots receive their seed in the Java creation paths.
ALTER TABLE `drama_shot`
    ADD COLUMN `seed` BIGINT DEFAULT NULL COMMENT '镜头视频渲染随机种子（JavaScript 安全整数）' AFTER `duration`;

UPDATE `drama_shot`
SET `seed` = FLOOR(1 + RAND() * 9007199254740991)
WHERE `seed` IS NULL;

ALTER TABLE `drama_shot`
    MODIFY COLUMN `seed` BIGINT NOT NULL COMMENT '镜头视频渲染随机种子（JavaScript 安全整数）';
