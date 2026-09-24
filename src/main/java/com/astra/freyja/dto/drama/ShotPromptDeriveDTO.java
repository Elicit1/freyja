package com.astra.freyja.dto.drama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分镜提示词智能流式衍生请求 DTO。
 * 支持传入抽屉中的即时编辑上下文，免持久化直接生成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShotPromptDeriveDTO {

    /** 分镜ID (可选，新建时为 null) */
    private Long shotId;

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属剧集ID */
    private Long episodeId;

    /** 归属场次ID */
    private Long sceneId;

    /** 镜头序号 */
    private Integer shotNo;

    /** 镜头标识 */
    private String shotName;

    /** 景别 (如 MEDIUM_SHOT, CLOSE_UP) */
    private String shotType;

    /** 运镜方式 (如 STATIC, PUSH_IN) */
    private String cameraMovement;

    /** 只有 true 才将 shotType 解释为创作者明确锁定；历史值默认不锁定。 */
    private Boolean shotTypeLocked;

    /** 只有 true 才将 cameraMovement 解释为创作者明确锁定；历史值默认不锁定。 */
    private Boolean cameraMovementLocked;

    /** 镜头时长 (秒) */
    private Double duration;

    /** 本镜头剧本文本 (Shot Script: 结合原文的核心剧本，供AI参考生成Prompt) */
    private String scriptContent;

    /** 画面动作与微观物理动态描述 (核心) */
    private String actionDescription;

    /** 对白台词 */
    private String dialogue;

    /** 对白说话人 */
    private String dialogueSpeaker;

    /** 旁白独白 */
    private String voiceover;

    /** 音效标注 */
    private String soundEffect;

    /** 绑定的场景资产ID */
    private Long resSceneId;

    /** 自定义场景描述覆盖 */
    private String customScenePrompt;

    /** 出场角色与造型装配列表 */
    private List<CharacterShotRefInfoVO> characterRefs;

    /** 涉及的关键道具列表 */
    private List<PropShotRefInfoVO> propRefs;

    /** 短剧全局画风预设 (若未传，后端自动从短剧获取) */
    private String stylePreset;

    /** 短剧视觉风格基调与导演指南 (若未传，后端自动从短剧获取) */
    private String styleTone;

    /** 剧集剧情简介 (若未传，后端自动从剧集获取) */
    private String episodeSummary;

    /** 创作者额外要求 (如: 逆光、冷色调、强调人物压迫感) */
    private String userInstruction;

    /** 指定 AI 提供商 ID */
    private Long providerId;

    /** 指定模型代码 */
    private String modelCode;

    /** API 模式下用户明确要求预加载的 Skill 名称。 */
    private List<String> requiredSkillNames;

    /** MANUAL 模式下要展开到复制 Prompt 的 Skill 名称。 */
    private List<String> selectedSkillNames;

    /** 生成模式: FIRST_LAST_FRAME(首尾帧模式)/REFERENCE_MODE(参考图与音频模式) */
    private String generationMode;

    /** 有序参考图片清单 (用于生成和对齐 Picture 1..N 标签) */
    private List<ShotRefImageDTO> refImages;

    /** 有序参考音频清单 (用于生成和对齐 Audio 1..N 标签) */
    private List<ShotRefAudioDTO> refAudios;

    /** @deprecated 系统已全量原生切换为 MiniMax H3 规范，此字段已废弃忽略 */
    @Deprecated
    private String promptTarget;

    /** 是否包含背景配乐 (BGM)，默认为 false (禁用 BGM，仅生成现场环境声与台词，为后期混音留出纯净声轨) */
    @Builder.Default
    private Boolean includeBgm = Boolean.FALSE;
}
