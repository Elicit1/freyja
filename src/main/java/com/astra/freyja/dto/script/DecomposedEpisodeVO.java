package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 剧本拆解提取的剧集信息 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecomposedEpisodeVO {

    @JsonPropertyDescription("集数序号，例如 1")
    private Integer episodeNo;

    @JsonPropertyDescription("本集标题，例如: 第1集：龙王归来遭嘲讽")
    private String title;

    @JsonPropertyDescription("本集剧情简介与高潮点")
    private String summary;

    @JsonPropertyDescription("原始剧本片段")
    private String scriptContent;

    @JsonPropertyDescription("目标时长(秒)，通常为 60~90")
    private Integer targetDuration;

    @JsonPropertyDescription("本集包含的情景场次列表")
    private List<DecomposedEpisodeSceneVO> scenes;
}
