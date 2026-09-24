package com.astra.freyja.dto.script;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * AI 任务 Token 预算与安全余量评估结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiBudgetEstimation implements Serializable {

    /** 估算的输入 Token 数 */
    private int estimatedInputTokens;

    /** 估算的预期输出 Token 数 */
    private int estimatedOutputTokens;

    /** 模型最大输出上限 (Max Output Tokens) */
    private int modelMaxOutputTokens;

    /** 安全预算上限 (保留 30%~40% 冗余后允许的最大输出) */
    private int safeOutputLimit;

    /** 是否超出安全预算 (若为 true 则需触发动态拆分) */
    private boolean exceedsBudget;

    /** 建议拆分份数 */
    private int recommendedSplitCount;

    /** 评估描述信息 */
    private String message;
}
