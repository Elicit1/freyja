package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Worker AI 针对单个 Segment 生成的结构化镜头输出 (WorkerShotResult)。
 * camera 只输出 AUTO 占位值；Worker 不设计摄影参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkerShotResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 所属剧情分段 ID (如 SEG001) */
    private String segmentId;

    /** 场次情景列表 */
    @JsonPropertyDescription("当前分段包含的情景/场次列表 (通常 1~3 场)")
    @Builder.Default
    private List<WorkerSceneVO> scenes = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkerSceneVO implements Serializable {
        private static final long serialVersionUID = 1L;

        @JsonPropertyDescription("局部场次ID，如 SC001")
        private String localId;

        @JsonPropertyDescription("引用的权威场景资产编号 (如 SC001，必须直接从资产库中选择引用)")
        private String sceneId;

        @JsonPropertyDescription("场景空间与具体情景中文名称 (如: 顶层总裁办公室、深夜昏暗地下车库)")
        private String name;

        @JsonPropertyDescription("分镜镜头列表")
        @Builder.Default
        private List<WorkerShotVO> shots = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkerShotVO implements Serializable {
        private static final long serialVersionUID = 1L;

        @JsonPropertyDescription("局部镜头ID，如 S001")
        private String localId;

        @JsonPropertyDescription("引用的权威场景资产编号 (如 SC001)")
        private String sceneId;

        @JsonPropertyDescription("局部镜头组ID，如 G001")
        private String groupLocalId;

        @JsonPropertyDescription("镜头组名称/连续动作描述，如: 葛明进门与杜宁对峙")
        private String groupName;

        @JsonPropertyDescription("段内镜头序号，从 1 开始递增")
        private Integer sequence;

        @JsonPropertyDescription("预估镜头时长（秒）；AUTO 按剧情估时，普通完整动作优先 3~5 秒；COMPACT 优先将普通完整动作组织在 3~5 秒内；完整对白或长动作可延长。按自然表演速度估算必须依次完成的动作和对白，并行动作共享时间，环境和持物状态不计时，不加保险缓冲；显式其他 pacingPreset 时遵循对应偏好")
        private Double duration;

        @JsonPropertyDescription("本镜头统一剧本：完整保留人物动作、逐字原文台词、画外音、旁白与独白，用一段简洁的连续动作链描述必要空间和道具状态变化，不反复复述完整起止状态。若原文明确提出摄影要求，原样放在‘原文摄影要求（用户明确指定）’段落中；不得添加自己的摄影设计")
        private String scriptContent;

        @JsonPropertyDescription("摄影参数占位对象。Worker 不设计摄影；shotSize 和 movement 必须都输出 AUTO")
        @JsonProperty(required = true)
        private WorkerCameraVO camera;

        @JsonPropertyDescription("画面动作简述与核心视觉动作描述 (纯中文，供剧作大纲与分镜列表概览)")
        private String action;

        @JsonPropertyDescription("出场角色姓名列表 (严格使用全局角色清单中的人物姓名或编号，如 [\"苏清雪\", \"苏明宇\"])")
        @Builder.Default
        private List<String> characterIds = new ArrayList<>();

        @JsonPropertyDescription("涉及的关键道具资产种类编号列表；此列表表示资产类型，不表示画面实例数量。具体数量、归属、左右手持有状态和状态变化写入 scriptContent")
        @Builder.Default
        private List<String> propIds = new ArrayList<>();

        @JsonPropertyDescription("音效或环境声描述")
        private String soundEffect;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkerCameraVO implements Serializable {
        private static final long serialVersionUID = 1L;

        @JsonPropertyDescription("固定输出 AUTO，表示景别待 Prompt AI 决定；Worker 不得选择具体景别")
        @JsonProperty(required = true)
        private String shotSize;

        @JsonPropertyDescription("固定输出 AUTO，表示运镜待 Prompt AI 决定；Worker 不得选择具体运镜")
        @JsonProperty(required = true)
        private String movement;
    }

}
