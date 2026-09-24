package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 分镜镜头实体 drama_shot。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_shot")
public class DramaShot extends BaseEntity {

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属剧集ID */
    private Long episodeId;

    /** 归属场次ID (drama_scene.id) */
    private Long sceneId;

    /** 归属镜头组ID (drama_shot_group.id) */
    private Long shotGroupId;

    /** 分镜序号 (1, 2, 3...) */
    private Integer shotNo;

    /** 镜头标识 (如: S01-01) */
    private String shotName;

    /** 景别 (字典 shot_type: EXTREME_CLOSE_UP/CLOSE_UP/MEDIUM_SHOT/FULL_SHOT...) */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String shotType;

    /** 运镜方式 (字典 camera_movement: STATIC/PUSH_IN/PULL_OUT/PAN_LEFT...) */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cameraMovement;

    /** 景别是否由创作者明确锁定；旧数据默认 false，不能依据历史字段值反推。 */
    private Boolean shotTypeLocked = false;

    /** 运镜是否由创作者明确锁定；旧数据默认 false，不能依据历史字段值反推。 */
    private Boolean cameraMovementLocked = false;

    /** 预估镜头时长 (秒) */
    private BigDecimal duration;

    /** 本镜头剧本文本 (Shot Script: 供后续AI参考原文与剧本生成Prompt) */
    private String scriptContent;

    /** 画面动作与视觉描述 */
    private String actionDescription;

    /** 对白台词 */
    private String dialogue;

    /** 台词说话人 */
    private String dialogueSpeaker;

    /** 旁白/内心独白 */
    private String voiceover;

    /** 音效描述 */
    private String soundEffect;

    /** 环境场景资产ID (为空则继承场次设置) */
    private Long resSceneId;

    /** 自定义场景提示词覆盖 */
    private String customScenePrompt;

    /** 多角色引用 JSON 数组 (含角色ID、造型ID、动作、表情、站位) */
    private String characterRefsJson;

    /** 关联道具引用 JSON 数组 (含道具ID、名称、类型、提示词) */
    private String propRefsJson;

    /** 最终正向提示词 (由 PromptAssemble 组装或手写) */
    private String prompt;

    /** 首帧生图专属提示词 (专供首帧 T2I 生图) */
    private String firstFramePrompt;

    /** 尾帧提示词 (专供尾帧 T2I 生图) */
    private String endFramePrompt;

    /** 最终负向提示词 */
    private String negativePrompt;

    /** 视频动态运镜提示词 (I2V 自然语言指令, 供 MiniMax H3 等视频模型) */
    private String videoPrompt;

    /** 结构化导演决策计划 JSON (DirectorPlan: 含 shotSize, cameraAngle, cameraBeats, narrativeIntent 等) */
    private String directorPlanJson;

    /** 生成模式 (字典 shot_generation_mode: FIRST_LAST_FRAME / REFERENCE_MODE) */
    private String generationMode;

    /** 尾帧图URL (MinIO URL) */
    private String endFrameImageUrl;

    /** 参考图列表 JSON (上限5个) */
    private String refImagesJson;

    /** 参考音频列表 JSON (上限4个, 单段3-8s, 总长20-30s) */
    private String refAudiosJson;

    /** 本镜生成视频末帧图 (MinIO URL, 供组内下一镜首帧续接) */
    private String lastFrameUrl;

    /** last_frame_url 对应的视频版本 URL，用于缓存失效判断 */
    private String lastFrameSourceVideoUrl;

    /** last_frame_url 对应的视频版本 Take ID，用于缓存失效判断 */
    private Long lastFrameSourceTakeId;

    /** 风格预设 (为空则继承短剧全局风格) */
    private String stylePreset;

    /** 首帧/关键帧预览图 (MinIO URL) */
    private String previewImageUrl;

    /** 首帧来源: MANUAL_UPLOAD/AI_GENERATED/PREVIOUS_VIDEO_TAIL */
    private String firstFrameSourceType;

    /** 首帧来自上一镜视频尾帧时的来源分镜ID */
    private Long firstFrameSourceShotId;

    /** 首帧引用时对应的来源视频版本URL */
    private String firstFrameSourceVideoUrl;

    /** 首帧来自上一镜视频尾帧时的来源 Take ID */
    private Long firstFrameSourceVideoTakeId;

    /** 最终生成视频 (MinIO URL) */
    private String videoUrl;

    /** 当前选中的分镜视频 Take ID */
    private Long currentVideoTakeId;

    /** TTS配音音频 (MinIO URL) */
    private String audioUrl;

    /** 渲染状态 (INIT/QUEUED/RENDERING/SUCCESS/FAILED) */
    private String renderStatus;

    /** 关联最新 ComfyUI 任务 ID */
    private String latestTaskId;

    /** 指定的 ComfyUI 工作流模板 */
    private String comfyWorkflowTemplateId;

    /** 显示排序 */
    private Integer sortOrder;
}
