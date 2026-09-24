package com.astra.freyja.dto.drama;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分镜提示词智能流式衍生响应 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotPromptDeriveVO {

    @JsonPropertyDescription("镜头主提示词/运镜动力Prompt (专供视频生成或整体视听指令)")
    private String prompt;

    @JsonPropertyDescription("首帧生图专属英文正向Prompt (首尾帧模式专属: 静态瞬间定格，融合焦点角色容貌服饰、环境空间构图与真实光影，严禁运镜词与时间闪烁词)")
    private String firstFramePrompt;

    @JsonPropertyDescription("尾帧生图专属英文正向Prompt (首尾帧模式专属: 动作演变终态瞬间定格，融合终态姿势神情与环境演变，严禁运镜词)")
    private String endFramePrompt;

    @JsonPropertyDescription("视频动态运镜专属英文Prompt (兼容旧字段，与 prompt 保持一致)")
    private String videoPrompt;

    /** 现场环境声、拟音与对白声场（结构化字段，供服务端最终渲染） */
    private String overallSoundscape;

    /** 非现场背景配乐设计（结构化字段，includeBgm=false 时由服务端强制忽略） */
    private String nonDiegeticMusic;

    @JsonPropertyDescription("分镜专属英文负向Prompt (排除画质缺陷、畸变、肢体残损与画风冲突词)")
    private String negativePrompt;

    @JsonPropertyDescription("结构化导演决策计划 (DirectorPlan: 含 Camera Beats, 景别机位与叙事意图)")
    private com.astra.freyja.director.model.DirectorPlan directorPlan;
}
