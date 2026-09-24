package com.astra.freyja.director.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化导演决策计划 (DirectorPlan)。
 * 第一阶段聚焦摄影决策与 Camera Beats，承上启下连接分镜配置与 H3 提示词生成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectorPlan implements Serializable {

    /** Schema 协议版本，第一阶段恒为 1.0 */
    @Builder.Default
    private String schemaVersion = "1.0";

    /** 镜头总时长 (秒) */
    private BigDecimal duration;

    /** 选定景别 (EXTREME_CLOSE_UP, CLOSE_UP, MEDIUM_CLOSE_UP, MEDIUM_SHOT, FULL_SHOT, LONG_SHOT, OVER_SHOULDER, TOP_DOWN) */
    private String shotSize;

    /** 选定机位与角度 (EYE_LEVEL, LOW_ANGLE, HIGH_ANGLE, DUTCH_ANGLE, OVER_THE_SHOULDER, BIRDS_EYE) */
    private String cameraAngle;

    /** 镜头内部按时间轴排序展开的运镜节拍序列 */
    @Builder.Default
    private List<CameraBeat> cameraBeats = new ArrayList<>();

    /** 主体动作演进描述 */
    private String subjectAction;

    /** 角色视线与焦点动向描述 (如 "由前方转向右侧阴影") */
    private String gaze;

    /** 导演叙事意图与戏剧目标 */
    private String narrativeIntent;

    /** 创作者导演锁定的字段列表 (如 ["duration"], ["duration", "shotSize"]) */
    @Builder.Default
    private List<String> lockedFields = new ArrayList<>();

    /** 规划生成时实际调用的 Skill 及其版本与哈希记录 */
    @Builder.Default
    private List<AppliedSkillRef> skills = new ArrayList<>();
}
