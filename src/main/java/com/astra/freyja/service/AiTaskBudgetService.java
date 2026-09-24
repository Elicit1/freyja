package com.astra.freyja.service;

import com.astra.freyja.dto.script.AiBudgetEstimation;

/**
 * AI 任务 Token 预算与安全余量控制服务。
 */
public interface AiTaskBudgetService {

    /**
     * 评估指定文本和任务类型的 Token 预算。
     *
     * @param inputText 输入文本
     * @param taskType 任务类型
     * @param modelMaxOutputTokens 模型最大输出 Token 上限 (如 4096, 8192)
     * @return 预算评估结果
     */
    AiBudgetEstimation estimateBudget(String inputText, String taskType, int modelMaxOutputTokens);

    /**
     * 默认最大输出上限 (8192) 评估预算。
     *
     * @param inputText 输入文本
     * @param taskType 任务类型
     * @return 预算评估结果
     */
    AiBudgetEstimation estimateBudget(String inputText, String taskType);
}
