package com.astra.freyja.dto.script;

import lombok.Data;
import com.astra.freyja.skill.model.SkillCatalogItem;

import java.util.List;

/**
 * 剧本智能拆解请求 DTO。
 */
@Data
public class ScriptDecomposeRequestDTO {

    /** AI 提供商 ID */
    private Long providerId;

    /** AI 模型代号 (如 deepseek-chat, gpt-4o, qwen2.5) */
    private String modelCode;

    /** 输入的待拆解原始文本 (小说章节/剧本/故事大纲) */
    private String rawText;

    /** 可选：关联已有短剧 ID（若在已有短剧内追加剧集或提取角色） */
    private Long dramaId;

    /** 可选：关联已有剧集 ID（若在具体剧集内触发拆解或历史隔离） */
    private Long episodeId;

    /** 可选：章节标题（如“第二章 家族逼宫”） */
    private String chapterTitle;

    /** 目标起始集号 (默认 1，若识别第二章可指定为 2) */
    private Integer startEpisodeNo;

    /** 期望拆解集数 (可选，为空则由 AI 自动规划) */
    private Integer targetEpisodes;

    /** 单集目标时长 (秒，默认 300) */
    private Integer targetDurationPerEpisode;

    /** 画幅比例 (9:16 / 16:9 / 1:1 / 4:3，默认 9:16) */
    private String aspectRatio;

    /** 画面风格预设 (为空则由 AI 推荐或默认 cinematic-realism) */
    private String stylePreset;

    /** 视觉风格基调/导演风格指南（自然语言融入，如光影、色调、镜头质感） */
    private String styleTone;

    /**
     * 剪辑节奏与镜头粒度偏好:
     * - STANDARD: 标准工业短剧 (默认, 5~8s/镜, 动作单元聚合, 对白过肩中景承载)
     * - CINEMATIC_LONG: 电影感长镜头 (7~12s/镜, 极少切镜, 运镜平稳连贯, 单场仅2~3镜)
     * - FAST_PACED: 快节奏高冲突 (2~4s/镜, 紧凑切镜, 密集反应与特写)
     */
    private String pacingPreset;

    /** Optional, independent Planner and Worker Skill policies. */
    private ScriptSkillPolicy skillPolicy;

    /** Server-owned version snapshot, persisted with the parent task for retry. Incoming values are overwritten. */
    private List<SkillCatalogItem> skillCatalogSnapshot;
}
