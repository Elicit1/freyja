package com.astra.freyja.entity.enums;

/**
 * AI 任务类型枚举。
 */
public enum AiTaskType {
    CHAPTER_DECOMPOSE("整章剧情并行分镜拆解"),
    SCRIPT_CHUNK("剧本切分"),
    PLOT_EXTRACTION("情节大纲提取"),
    ENTITY_RESOLUTION("角色场景实体消歧"),
    SHOT_GROUP_EXTRACTION("连续镜头组提取"),
    SHOT_DECOMPOSE("分镜拆解"),
    SHOT_ENRICHMENT("分镜特征增强"),
    CONTINUITY_CHECK("镜头连续性分析"),
    SHOT_PROMPT_DERIVE("分镜提示词分析"),
    CHARACTER_PROMPT_DERIVE("角色身份提示词衍生"),
    LOOK_PROMPT_DERIVE("角色造型提示词衍生"),
    SCENE_PROMPT_DERIVE("场景提示词衍生"),
    PROP_PROMPT_DERIVE("道具提示词衍生"),
    SHOT_VISUAL_PLAN("分镜视觉导演规划"),
    CHARACTER_VOICE_DESIGN("角色母音设计"),
    VOICE_PREVIEW("音色试听"),
    SHOT_VOICE_GENERATE("分镜配音"),
    EPISODE_VOICE_GENERATE("剧集批量配音"),
    SHOT_FRAME_GENERATE("分镜关键帧生图"),
    ASSET_IMAGE_GENERATE("资产生图"),
    EPISODE_DECOMPOSE("单集剧本拆解"),
    WORKER_RETRY("分段 Worker 重试");

    private final String description;

    AiTaskType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
