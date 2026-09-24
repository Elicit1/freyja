package com.astra.freyja.director.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 镜头内部随剧情动作展开的单个运镜节拍 (Camera Beat)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraBeat implements Serializable {

    /** 节拍起始时间点 (秒, 相对本镜头起始点 0.0) */
    private BigDecimal startSec;

    /** 节拍结束时间点 (秒) */
    private BigDecimal endSec;

    /** 摄影机运动类型 (STATIC, PUSH_IN, PULL_OUT, PAN_LEFT, PAN_RIGHT, TILT_UP, TILT_DOWN, TRACKING, ORBIT, ZOOM_IN) */
    private String movement;

    /** 运动方向 (FORWARD, BACKWARD, LEFT, RIGHT, UP, DOWN; STATIC 时为 null) */
    private String direction;

    /** 运动速度 (NONE, SLOW, NORMAL, FAST; STATIC 时为 NONE) */
    private String speed;

    /** 触发运镜起始的剧情/动作节点 (如 "角色进入房间并在桌前停步") */
    private String startCue;

    /** 运镜收束或转折的剧情/动作节点 (如 "视线转向右侧阴影") */
    private String stopCue;

    /** 该运镜节拍的叙事戏剧目的 (如 "先识别人物主体与初始空间") */
    private String narrativePurpose;
}
