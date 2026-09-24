package com.astra.freyja.dto.drama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 外部 AI 返回结果解析与校验请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShotPromptParseRequestDTO {

    /** 分镜ID (可选) */
    private Long shotId;

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属剧集ID */
    private Long episodeId;

    /** 归属场次ID */
    private Long sceneId;

    /** 镜头序号 */
    private Integer shotNo;

    /** 生成模式: REFERENCE_MODE / FIRST_LAST_FRAME */
    private String generationMode;

    /** 外部 AI 返回的原始纯文本 (支持包含 `json 代码块或少量说明) */
    private String rawResponse;

    /** 导出任务包时的上下文指纹 (用于比对校验镜头上下文是否在复制期间发生变动) */
    private String contextFingerprint;

    /** 台词对白 (用于校验 <d> 标签是否正确包裹) */
    private String dialogue;

    /** 出场角色列表 */
    private List<CharacterShotRefInfoVO> characterRefs;

    /** 关键道具设定列表 */
    private List<PropShotRefInfoVO> propRefs;

    /** 有序参考图片清单 (用于校验 Picture 1..N 标签越界) */
    private List<ShotRefImageDTO> refImages;

    /** 有序参考音频清单 (用于校验 Audio 1..N 标签越界) */
    private List<ShotRefAudioDTO> refAudios;

    /** 是否包含背景配乐 (BGM)，默认为 false */
    @Builder.Default
    private Boolean includeBgm = Boolean.FALSE;

    /** 导出任务包时用户选择并展开的 Skill 名称。 */
    private List<String> selectedSkillNames;
}
