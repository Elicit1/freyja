-- Optional bulk migration for an existing MySQL database. No DROP/TRUNCATE.
-- The application also projects legacy speech into the editor before migration;
-- saving the unified script retires the old speech fields for that shot.
-- Backups preserve original script/speech/audio before an idempotent migration.
CREATE TABLE IF NOT EXISTS drama_shot_legacy_script_backup (
    shot_id BIGINT NOT NULL PRIMARY KEY,
    script_content LONGTEXT,
    dialogue_speaker VARCHAR(255),
    dialogue LONGTEXT,
    voiceover LONGTEXT,
    audio_url LONGTEXT,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

START TRANSACTION;
INSERT IGNORE INTO drama_shot_legacy_script_backup
    (shot_id, script_content, dialogue_speaker, dialogue, voiceover, audio_url)
SELECT id, script_content, dialogue_speaker, dialogue, voiceover, audio_url
FROM drama_shot
WHERE NULLIF(TRIM(dialogue), '') IS NOT NULL OR NULLIF(TRIM(voiceover), '') IS NOT NULL
   OR NULLIF(TRIM(audio_url), '') IS NOT NULL;

UPDATE drama_shot
SET script_content = NULLIF(CONCAT_WS('\n\n', NULLIF(script_content, ''),
    CASE WHEN NULLIF(TRIM(dialogue), '') IS NOT NULL THEN
        CONCAT('【历史对白，请核对】\n', COALESCE(NULLIF(TRIM(dialogue_speaker), ''), '未指定说话人'), '：“', dialogue, '”') END,
    CASE WHEN NULLIF(TRIM(voiceover), '') IS NOT NULL THEN
        CONCAT('【历史旁白 / 独白，请核对】\n', voiceover) END), ''),
    dialogue = NULL, dialogue_speaker = NULL, voiceover = NULL
WHERE NULLIF(TRIM(dialogue), '') IS NOT NULL OR NULLIF(TRIM(voiceover), '') IS NOT NULL;

-- Historical audio has been archived. New generation only consumes explicit ref_audios_json.
UPDATE drama_shot SET audio_url = NULL WHERE audio_url IS NOT NULL;
COMMIT;

-- Do not re-run init.sql against an existing database: its DROP/REPLACE statements
-- can remove business data or creator-customized prompts. The application overlays
-- the unified Worker/speech contract on configured prompts, including cached versions.
