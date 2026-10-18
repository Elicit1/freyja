-- Additive, repeatable upgrade. No destructive changes to legacy character columns.
CREATE TABLE IF NOT EXISTS character_voice_audio (
 id BIGINT NOT NULL PRIMARY KEY, character_id BIGINT NOT NULL, base_audio_id BIGINT NULL,
 kind VARCHAR(24) NOT NULL, name VARCHAR(128) NOT NULL, text TEXT NULL, emotion VARCHAR(100) NULL,
 voice_desc VARCHAR(512) NULL, raw_audio_url VARCHAR(1024) NULL, raw_duration DOUBLE NULL,
 processed_audio_url VARCHAR(1024) NULL, processed_duration DOUBLE NULL, effects_json JSON NOT NULL,
 revision INT NOT NULL DEFAULT 1, status VARCHAR(32) NOT NULL, error_message VARCHAR(1000) NULL,
 provider_id BIGINT NULL, model_code VARCHAR(128) NULL, task_id BIGINT NULL,
 create_by BIGINT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 update_by BIGINT NULL, update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(500) NULL,
 active_base_character BIGINT GENERATED ALWAYS AS (CASE WHEN deleted=0 AND kind='BASE' THEN character_id ELSE NULL END) STORED,
 UNIQUE KEY uk_active_base_character(active_base_character),
 KEY idx_character_voice(character_id,deleted,kind,create_time), KEY idx_voice_base(base_audio_id),
 CONSTRAINT chk_base_dry CHECK (kind='CLONE' OR JSON_LENGTH(effects_json)=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS voice_effect (
 id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, description VARCHAR(1000) NULL,
 steps_json JSON NOT NULL, version INT NOT NULL DEFAULT 1, enabled TINYINT NOT NULL DEFAULT 1,
 create_by BIGINT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 update_by BIGINT NULL, update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted TINYINT NOT NULL DEFAULT 0, remark VARCHAR(500) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO character_voice_audio(id,character_id,kind,name,text,voice_desc,raw_audio_url,effects_json,status)
 SELECT c.id,c.id,'BASE','基础参考音频',c.voice_sample_text,c.voice_desc,c.voice_sample_url,'[]','READY'
 FROM res_character c WHERE c.deleted=0 AND c.voice_sample_url IS NOT NULL AND c.voice_sample_url!=''
 AND NOT EXISTS(SELECT 1 FROM character_voice_audio a WHERE a.character_id=c.id);

INSERT INTO sys_dict_type(id,dict_type,dict_name,status)
 SELECT 86000,'voice_emotion','配音情绪',1 WHERE NOT EXISTS(SELECT 1 FROM sys_dict_type WHERE dict_type='voice_emotion');
INSERT IGNORE INTO sys_dict_data(id,dict_type,dict_label,dict_value,sort_order,status) VALUES
 (86001,'voice_emotion','平静','平静',1,1),(86002,'voice_emotion','喜悦','喜悦',2,1),
 (86003,'voice_emotion','愤怒','愤怒',3,1),(86004,'voice_emotion','悲伤','悲伤',4,1),
 (86005,'voice_emotion','恐惧','恐惧',5,1),(86006,'voice_emotion','惊讶','惊讶',6,1),
 (86007,'voice_emotion','兴奋','兴奋',7,1),(86008,'voice_emotion','温柔','温柔',8,1);

INSERT IGNORE INTO voice_effect(id,name,description,steps_json) VALUES
 (86101,'无线电','窄频带、压缩与轻微失真','[{"type":"highpass","params":{"frequency":400}},{"type":"lowpass","params":{"frequency":3400}},{"type":"compressor","params":{"threshold":0.2,"ratio":5}},{"type":"distortion","params":{"amount":0.08}}]'),
 (86102,'电话','电话听筒频段','[{"type":"highpass","params":{"frequency":300}},{"type":"lowpass","params":{"frequency":3000}}]'),
 (86103,'回声','延迟回声','[{"type":"echo","params":{"delay":200,"decay":0.35}}]'),
 (86104,'男女双声','原声与变调声叠加，不生成独立演员','[{"type":"dual","params":{"semitones":5,"mix":0.5,"delay":20}}]'),
 (86105,'混响','多次短延迟模拟空间反射','[{"type":"reverb","params":{"amount":0.3}}]'),
 (86106,'变调','改变音高并补偿语速','[{"type":"pitch","params":{"semitones":3}}]');
