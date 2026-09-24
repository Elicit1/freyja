package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 剧本智能拆解总结果 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptDecomposeResultVO {

    @JsonPropertyDescription("根据故事提取或提炼的短剧标题")
    private String dramaTitle;

    @JsonPropertyDescription("题材分类，例如: DOMINANT_CEO(霸总), WAR_GOD(战神), URBAN_ABILITY(都市异能), TIME_TRAVEL(穿越), ANCIENT_COSTUME(古装), SUSPENSE(悬疑), COMEDY(搞笑)")
    private String genre;

    @JsonPropertyDescription("故事梗概与核心大纲")
    private String synopsis;

    @JsonPropertyDescription("画幅比例 (9:16 / 16:9 / 1:1)")
    private String aspectRatio;

    @JsonPropertyDescription("推荐的画面风格预设，例如: cinematic-realism, 3d-animation, anime-makoto, cyber-realism, retro-film")
    private String stylePreset;

    @JsonPropertyDescription("视觉风格基调/导演风格指南（自然语言融入，如光影、色调、镜头质感）")
    private String styleTone;

    @JsonPropertyDescription("提取的所有出场人物角色列表")
    private List<DecomposedCharacterVO> characters;

    @JsonPropertyDescription("提取的所有环境场景列表")
    private List<DecomposedSceneVO> scenes;

    @JsonPropertyDescription("提取的所有关键道具列表")
    private List<DecomposedPropVO> props;

    @JsonPropertyDescription("拆解后的分集大纲及场次分镜对白流")
    private List<DecomposedEpisodeVO> episodes;

    @JsonPropertyDescription("Planner 规划的剧情分段列表 (StorySegments)")
    private List<StorySegment> segments;

    @JsonPropertyDescription("镜头时长与碎片率统计指标")
    private FragmentationStatsVO fragmentationStats;

    @JsonPropertyDescription("归属持久化任务 ID (AiTask.id)")
    private Long taskId;

    @JsonPropertyDescription("任务状态 (RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED)")
    private String status;

    @JsonPropertyDescription("各 Worker 分段执行结果与状态列表 (含各分段成功/失败及错误明细)")
    private List<SegmentShotResult> segmentResults;

    /** Actual required preloads and load_skill results observed by the server. */
    private List<ScriptSkillEvent> skillEvents;
}
