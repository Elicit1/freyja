package com.astra.freyja.dto.drama;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分镜镜头展示 VO。
 */
@Data
public class DramaShotVO {

    private Long id;
    private Long dramaId;
    private Long episodeId;
    private Long sceneId;
    private Long shotGroupId;
    private Integer shotNo;
    private String shotName;
    private String shotType;
    private String cameraMovement;
    private Boolean shotTypeLocked;
    private Boolean cameraMovementLocked;
    private BigDecimal duration;
    private String scriptContent;
    private String actionDescription;
    private String dialogue;
    private String dialogueSpeaker;
    private String voiceover;
    private String soundEffect;

    /** 关联环境场景信息 */
    private Long resSceneId;
    private String resSceneName;
    private String resSceneCoverUrl;
    private String customScenePrompt;

    /** 结构化角色及造型列表 */
    private List<CharacterShotRefInfoVO> characterRefs;

    /** 关联道具引用列表 */
    private List<PropShotRefInfoVO> propRefs;

    /** 提示词与风格 */
    private String prompt;
    private String firstFramePrompt;
    private String endFramePrompt;
    private String negativePrompt;
    private String videoPrompt;
    private String stylePreset;

    /** 结构化导演决策计划 JSON，供分镜详情按镜头独立回显。 */
    private String directorPlanJson;

    /** 生成模式与首尾帧/多模态配置 */
    private String generationMode;
    private String endFrameImageUrl;
    private List<ShotRefImageDTO> refImages;
    private List<ShotRefAudioDTO> refAudios;

    /** 产物媒体 */
    private String previewImageUrl;
    private String firstFrameSourceType;
    private Long firstFrameSourceShotId;
    private String firstFrameSourceVideoUrl;
    private Long firstFrameSourceVideoTakeId;
    private String videoUrl;
    private Long currentVideoTakeId;
    private Integer videoTakeCount;
    private String audioUrl;
    private String lastFrameUrl;
    private String lastFrameSourceVideoUrl;
    private Long lastFrameSourceTakeId;

    /** 渲染状态与调度关联 */
    private String renderStatus;
    private String latestTaskId;
    private Integer renderProgress;
    private String currentNode;
    private String comfyWorkflowTemplateId;

    private Integer sortOrder;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
