package com.astra.freyja.dto.script;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Stage 2 轻量分镜 VO。
 * 遵循 Token 极简原则，仅引用 characterIds/locationId/propIds，严禁输出角色与场景完整描述。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LightweightShotVO implements Serializable {

    /** 镜头序号 (1, 2, 3...) */
    private Integer shotNo;

    /** 镜头标识 (如 S01-01) */
    private String shotName;

    /** 景别 (EXTREME_CLOSE_UP, CLOSE_UP, MEDIUM_SHOT, FULL_SHOT, OVER_SHOULDER 等) */
    private String shotType;

    /** 运镜 (STATIC, PUSH_IN, PULL_OUT, TRACKING 等) */
    private String cameraMovement;

    /** 预估时长 (秒) */
    private Double duration;

    /** 画面动作与视觉描述 */
    private String actionDescription;

    /** 视频动态运镜提示词 (专供图生视频 I2V) */
    private String videoPrompt;

    /** 台词说话人 */
    private String dialogueSpeaker;

    /** 对白台词 */
    private String dialogue;

    /** 旁白/内心独白 */
    private String voiceover;

    /** 音效描述 */
    private String soundEffect;

    /** 关联角色名称/提及列表 (用于消歧对齐) */
    private List<String> characterNames;

    /** 关联角色 ID 列表 (如 ["C001", "C002"]) */
    private List<Long> characterIds;

    /** 关联场景名称 */
    private String sceneName;

    /** 关联场景 ID */
    private Long locationId;

    /** 关联道具 ID 列表 */
    private List<String> propIds;

    /** 归属连续镜头组标识 (如 G001) */
    private String groupId;
}
