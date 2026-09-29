package com.astra.freyja.dto.drama;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/**
 * MiniMax H3 多模态参考图模式 (Ref2VA) 大模型纯提示词输出载荷。
 * 仅包含大模型需要生成的提示词字段，严禁包含庞大的 DirectorPlan，避免大模型单次输出 token 耗尽截断。
 */
@Data
public class H3Ref2VaPromptOutput {

    @JsonPropertyDescription("完整 MiniMax H3 多模态视频提示词 (Ref2VA Prompt)，包含 subject_definitions、summary、retention_analysis、detailed_description 等完整规范段落")
    private String prompt;

    @JsonPropertyDescription("现场环境声、拟音与对白声场设计 (若已包含在 prompt 中可与 overall_soundscape 保持一致)")
    private String overallSoundscape;

    @JsonPropertyDescription("非剧情背景音乐 (通常填 N/A)")
    private String nonDiegeticMusic;

    @JsonPropertyDescription("分镜专属英文负向提示词 (排除画质缺陷、畸变、肢体残损与画风冲突词)")
    private String negativePrompt;
}
