package com.astra.freyja.dto.drama;

import com.astra.freyja.dto.res.CharacterShotRefDTO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分镜镜头新增/修改请求 DTO。
 */
@Data
public class DramaShotDTO {

    private Long id;

    /** 归属短剧 ID */
    private Long dramaId;

    /** 归属剧集 ID */
    private Long episodeId;

    /** 归属场次 ID (drama_scene.id) */
    private Long sceneId;

    /** 归属镜头组 ID (drama_shot_group.id) */
    private Long shotGroupId;

    /** 分镜序号 (1, 2, 3...) */
    private Integer shotNo;

    /** 镜头标识 (如: S01-01) */
    private String shotName;

    /** 景别 (字典 shot_type) */
    private String shotType;

    /** 运镜方式 (字典 camera_movement) */
    private String cameraMovement;

    /** 当前景别值是否由创作者主动选择并要求导演规划严格遵守 */
    private Boolean shotTypeLocked;

    /** 当前运镜值是否由创作者主动选择并要求导演规划严格遵守 */
    private Boolean cameraMovementLocked;

    /** 预估镜头时长 (秒) */
    private BigDecimal duration;

    /** 本镜头剧本文本 (Shot Script) */
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

    /** 环境场景资产 ID (为空则继承场次设置) */
    private Long resSceneId;

    /** 自定义场景提示词覆盖 */
    private String customScenePrompt;

    /** 多角色引用列表 (前端传入列表，后端转换为 JSON 存储) */
    private List<CharacterShotRefDTO> characterRefs;

    /** 关联道具引用列表 (前端传入列表，后端转换为 JSON 存储) */
    private List<PropShotRefDTO> propRefs;

    /** 最终正向提示词 (由 PromptAssemble 组装或手写) */
    private String prompt;

    /** 首帧生图专属提示词 (专供首帧 T2I 生图) */
    private String firstFramePrompt;

    /** 尾帧提示词 (专供尾帧 T2I 生图) */
    private String endFramePrompt;

    /** 最终负向提示词 */
    private String negativePrompt;

    /** 视频动态运镜提示词 (I2V 自然语言指令) */
    private String videoPrompt;

    /** 生成模式 (FIRST_LAST_FRAME / REFERENCE_MODE) */
    private String generationMode;

    /** 尾帧图URL (MinIO URL) */
    private String endFrameImageUrl;

    /** 参考图列表 (上限9张) */
    private List<ShotRefImageDTO> refImages;

    /** 参考音频列表 (上限3段, 单段2-15s, 总长≤15s) */
    private List<ShotRefAudioDTO> refAudios;

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

    /** 本镜生成视频末帧图 (MinIO URL, 供组内下一镜首帧续接) */
    private String lastFrameUrl;

    /** last_frame_url 对应的视频版本 URL，用于缓存失效判断 */
    private String lastFrameSourceVideoUrl;

    /** last_frame_url 对应的视频版本 Take ID，用于缓存失效判断 */
    private Long lastFrameSourceTakeId;

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

    /** 备注 */
    private String remark;
}
