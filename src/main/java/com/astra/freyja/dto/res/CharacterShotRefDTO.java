package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 分镜中引用的角色及特定行为/造型参数。
 */
@Data
public class CharacterShotRefDTO {

    /** 角色 ID */
    private Long characterId;

    /** 可选的角色视觉造型 ID；为空表示本分镜不指定独立造型。 */
    private Long lookId;

    /** 造型视觉概念描述 (颜色/面料/轮廓/配饰/妆发) */
    private String designDesc;

    /** 此分镜中的动作描述 (如: sitting at desk, typing on keyboard) */
    private String actionPrompt;

    /** 此分镜中的情绪/表情 (如: smiling gently, looking focused) */
    private String emotionPrompt;

    /** 画面位置/构图描述 (如: center foreground, on the left) */
    private String positionTag;
}
