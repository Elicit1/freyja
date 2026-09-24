package com.astra.freyja.service;

import com.astra.freyja.dto.script.DecomposedPlotVO;

import java.util.List;

/**
 * 任务动态切分器。
 * 当单个 Plot 或任务载荷超出 Token 预算时，自动分治为多个子任务。
 */
public interface AiTaskSplitter {

    /**
     * 检查并对可能超标的 Plot 进行动态分治拆解。
     *
     * @param plot 待评估情节
     * @param modelMaxOutputTokens 模型最大输出 Token 上限
     * @return 拆分后的 Plot 单元列表 (若未超标则返回原单个 Plot)
     */
    List<DecomposedPlotVO> splitPlotIfExceedsBudget(DecomposedPlotVO plot, int modelMaxOutputTokens);
}
