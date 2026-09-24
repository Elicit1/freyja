package com.astra.freyja.service.impl;

import com.astra.freyja.dto.script.AiBudgetEstimation;
import com.astra.freyja.service.AiTaskBudgetService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * AI 任务 Token 预算与安全余量控制服务实现。
 */
@Slf4j
@Service
public class AiTaskBudgetServiceImpl implements AiTaskBudgetService {

    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;
    private static final double SAFETY_MARGIN_RATIO = 0.65; // 保留 35% 安全冗余

    @Override
    public AiBudgetEstimation estimateBudget(String inputText, String taskType) {
        return estimateBudget(inputText, taskType, DEFAULT_MAX_OUTPUT_TOKENS);
    }

    @Override
    public AiBudgetEstimation estimateBudget(String inputText, String taskType, int modelMaxOutputTokens) {
        int charCount = StringUtils.isNotBlank(inputText) ? inputText.length() : 0;

        // 中文字符换算 Token 比例约 1.5 ~ 1.8，外加 Prompt 开销
        int estimatedInputTokens = (int) Math.ceil(charCount * 1.6) + 300;

        // 根据任务类型预估输出 Token
        int estimatedOutputTokens;
        if ("PLOT_EXTRACTION".equalsIgnoreCase(taskType)) {
            // 情节大纲通常为轻量输出 (300 ~ 1000 tokens)
            estimatedOutputTokens = Math.max(400, (int) (charCount * 0.4));
        } else if ("SHOT_DECOMPOSE".equalsIgnoreCase(taskType)) {
            // 分镜拆解: 每个镜头组约 200~400 tokens，每 100 字剧本约产生 1 个镜头 (~150 tokens)
            estimatedOutputTokens = Math.max(600, (int) (charCount * 1.8));
        } else if ("SHOT_ENRICHMENT".equalsIgnoreCase(taskType)) {
            estimatedOutputTokens = 500;
        } else {
            estimatedOutputTokens = Math.max(500, charCount);
        }

        int safeOutputLimit = (int) (modelMaxOutputTokens * SAFETY_MARGIN_RATIO);
        boolean exceedsBudget = estimatedOutputTokens > safeOutputLimit;
        int recommendedSplitCount = exceedsBudget
                ? (int) Math.ceil((double) estimatedOutputTokens / (safeOutputLimit * 0.8))
                : 1;

        String msg = exceedsBudget
                ? String.format("预期输出 %d tokens 超过安全上限 %d tokens (模型上限 %d), 建议拆分为 %d 个细化任务",
                estimatedOutputTokens, safeOutputLimit, modelMaxOutputTokens, recommendedSplitCount)
                : String.format("预算充足: 预估输出 %d tokens (安全上限 %d)", estimatedOutputTokens, safeOutputLimit);

        log.debug("[AiTaskBudget] 评估完成: taskType={}, charCount={}, exceedsBudget={}, splits={}",
                taskType, charCount, exceedsBudget, recommendedSplitCount);

        return AiBudgetEstimation.builder()
                .estimatedInputTokens(estimatedInputTokens)
                .estimatedOutputTokens(estimatedOutputTokens)
                .modelMaxOutputTokens(modelMaxOutputTokens)
                .safeOutputLimit(safeOutputLimit)
                .exceedsBudget(exceedsBudget)
                .recommendedSplitCount(recommendedSplitCount)
                .message(msg)
                .build();
    }
}
