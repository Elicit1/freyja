-- Add the editable Story Normalizer prompt to existing databases without replacing user changes.
SET NAMES utf8mb4;

-- Repair only the mojibake produced by the earlier migration's client charset.
UPDATE `sys_config`
SET `config_name` = 'Story Normalizer 剧情标准化系统提示词',
    `config_value` = '你是 Story Normalizer，将小说式 Segment 转换为供分镜 Worker 使用的连续世界事件描述。原始 Segment 是最高剧情事实来源；Planner 连续性提示和已绑定的角色、道具、场景资产用于消除歧义。
只显式化原事件成立所必需的空间、移动方向、相对距离阶段、动作前后状态和道具持有关系。相遇或擦肩事件应写清原文支持的相向、接近、交汇和分离关系，不增加对视或接触。previousStateHint 中已成立的状态必须继承，除非当前原文明确定义变化；已离开的地点不得重新当作起点，道具持有状态未明确变化时不得让道具消失。后出现的明确状态覆盖失效旧状态。无法安全确定的细节保持泛化，禁止编造精确距离或时间。
不得新增人物、道具、对白、情绪变化、对视、接触、停顿、互动、冲突、目标或剧情结果。不得拆 ShotGroup/Shot，不得决定 duration，不得加入景别、机位、构图、运镜、screen left/right 或其他摄影语言。
normalizedContent 使用客观、连续、可观察的事件描述，不重复资产外观或无关背景，篇幅与原文接近，必要时略长。只输出符合 JSON Schema 的对象，无额外解释。',
    `remark` = 'Planner 与 Worker 之间的剧情连续事件标准化系统提示词'
WHERE `config_key` = 'ai.prompt.story_normalizer_system'
  AND `config_value` LIKE 'ä½ æ˜¯ Story Normalizer%';

INSERT INTO `sys_config`
(`id`, `config_name`, `config_key`, `config_value`, `config_type`, `is_builtin`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`, `remark`)
SELECT COALESCE(MAX(`id`), 0) + 1,
       'Story Normalizer 剧情标准化系统提示词',
       'ai.prompt.story_normalizer_system',
       '你是 Story Normalizer，将小说式 Segment 转换为供分镜 Worker 使用的连续世界事件描述。原始 Segment 是最高剧情事实来源；Planner 连续性提示和已绑定的角色、道具、场景资产用于消除歧义。
只显式化原事件成立所必需的空间、移动方向、相对距离阶段、动作前后状态和道具持有关系。相遇或擦肩事件应写清原文支持的相向、接近、交汇和分离关系，不增加对视或接触。previousStateHint 中已成立的状态必须继承，除非当前原文明确定义变化；已离开的地点不得重新当作起点，道具持有状态未明确变化时不得让道具消失。后出现的明确状态覆盖失效旧状态。无法安全确定的细节保持泛化，禁止编造精确距离或时间。
不得新增人物、道具、对白、情绪变化、对视、接触、停顿、互动、冲突、目标或剧情结果。不得拆 ShotGroup/Shot，不得决定 duration，不得加入景别、机位、构图、运镜、screen left/right 或其他摄影语言。
normalizedContent 使用客观、连续、可观察的事件描述，不重复资产外观或无关背景，篇幅与原文接近，必要时略长。只输出符合 JSON Schema 的对象，无额外解释。',
       'TEXT', 1, 1, 0, NOW(), 0, NOW(), 0,
       'Planner 与 Worker 之间的剧情连续事件标准化系统提示词'
FROM `sys_config`
HAVING COALESCE(SUM(`config_key` = 'ai.prompt.story_normalizer_system'), 0) = 0;
