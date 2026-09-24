package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局短剧/章节上下文信息 (GlobalStoryContext)。
 * 在整章处理开始时由 Java 一次性加载并缓存，避免 Worker 重复查询数据库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GlobalStoryContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 短剧 ID (如有) */
    private Long dramaId;

    /** 剧集 ID (如有) */
    private Long episodeId;

    /** 归属持久化任务 ID (AiTask.id) */
    private Long taskId;

    /** 短剧标题 */
    private String dramaTitle;

    /** 题材类型 */
    private String genre;

    /** 画幅比例 (如 9:16, 16:9) */
    @Builder.Default
    private String aspectRatio = "9:16";

    /** 画面风格预设 (如 cinematic-realism, 3d-animation) */
    @Builder.Default
    private String stylePreset = "cinematic-realism";

    /** 视觉风格基调/导演风格指南（自然语言融入，如光影、色调、镜头质感） */
    private String styleTone;

    /** 剪辑节奏预设 (STANDARD, CINEMATIC_LONG, FAST_PACED) */
    @Builder.Default
    private String pacingPreset = "STANDARD";

    /** 目标起始集号 (默认 1) */
    @Builder.Default
    private Integer startEpisodeNo = 1;

    /** 章节/剧集标题 */
    private String chapterTitle;

    /** 角色注册表上下文 (格式化好的 Prompt 文本与条目) */
    private String characterRegistryPromptText;

    /** 场景资产库上下文 (格式化好的 Prompt 文本与条目，供 Worker 按 ID 强引用) */
    private String sceneRegistryPromptText;

    /** 道具资产库上下文 (格式化好的 Prompt 文本与条目，供 Worker 按 ID 强引用) */
    private String propRegistryPromptText;

    /** 识别或已注册的角色资产列表 */
    @Builder.Default
    private List<DecomposedCharacterVO> characters = new ArrayList<>();

    /** 识别或已注册的场景资产列表 */
    @Builder.Default
    private List<DecomposedSceneVO> scenes = new ArrayList<>();

    /** 识别或已注册的道具资产列表 */
    @Builder.Default
    private List<DecomposedPropVO> props = new ArrayList<>();

    /** 扩展元数据缓存 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /** Shared result audit trail; every Worker has its own tool session. */
    @Builder.Default
    private List<ScriptSkillEvent> skillEvents = new CopyOnWriteArrayList<>();
}
