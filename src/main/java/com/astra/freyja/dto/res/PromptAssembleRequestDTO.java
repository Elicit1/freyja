package com.astra.freyja.dto.res;

import lombok.Data;

import java.util.List;

/**
 * 分镜 Prompt 动态组装请求。
 */
@Data
public class PromptAssembleRequestDTO {

    /** 所属短剧 ID (可选) */
    private Long dramaId;

    /** 所选场景 ID (可选) */
    private Long sceneId;

    /** 自定义场景 Prompt（当不选择场景资产或需覆盖时使用） */
    private String customScenePrompt;

    /** 所选角色引用列表 */
    private List<CharacterShotRefDTO> characterRefs;

    /** 分镜景别/运镜/构图 Prompt (如: masterpiece, best quality, cinematic shot, medium shot, 8k) */
    private String shotPrompt;

    /** 附加自定义正向词 */
    private String customPositivePrompt;

    /** 附加自定义负向词 */
    private String customNegativePrompt;

    /** 风格预设 (如: cinematic-realism, 3d-pixar, anime-makoto) */
    private String stylePreset;
}
