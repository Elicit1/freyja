package com.astra.freyja.service.impl;

import com.astra.freyja.dto.script.AiBudgetEstimation;
import com.astra.freyja.dto.script.DecomposedPlotVO;
import com.astra.freyja.dto.script.ScriptChunkVO;
import com.astra.freyja.service.AiTaskBudgetService;
import com.astra.freyja.service.AiTaskSplitter;
import com.astra.freyja.service.ScriptChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务动态切分器实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskSplitterImpl implements AiTaskSplitter {

    private final AiTaskBudgetService budgetService;
    private final ScriptChunkService chunkService;

    @Override
    public List<DecomposedPlotVO> splitPlotIfExceedsBudget(DecomposedPlotVO plot, int modelMaxOutputTokens) {
        if (plot == null) {
            return List.of();
        }

        String rawText = StringUtils.defaultIfBlank(plot.getRawText(), plot.getSummary());
        if (StringUtils.isBlank(rawText)) {
            return List.of(plot);
        }

        AiBudgetEstimation budget = budgetService.estimateBudget(rawText, "SHOT_DECOMPOSE", modelMaxOutputTokens);
        if (!budget.isExceedsBudget() || budget.getRecommendedSplitCount() <= 1) {
            return List.of(plot);
        }

        log.warn("[AiTaskSplitter] Plot (id={}) 超出 Token 预算, 启动动态二次切分, 预计拆分为 {} 份",
                plot.getPlotId(), budget.getRecommendedSplitCount());

        int targetChunkSize = Math.max(500, rawText.length() / budget.getRecommendedSplitCount());
        List<ScriptChunkVO> chunks = chunkService.splitIntoChunks(rawText, targetChunkSize, 80);

        if (chunks.isEmpty() || chunks.size() == 1) {
            return List.of(plot);
        }

        List<DecomposedPlotVO> subPlots = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ScriptChunkVO ch = chunks.get(i);
            String subId = String.format("%s_SUB%02d", plot.getPlotId(), i + 1);
            DecomposedPlotVO subPlot = DecomposedPlotVO.builder()
                    .plotId(subId)
                    .sequence(plot.getSequence() != null ? (plot.getSequence() * 10 + (i + 1)) : (i + 1))
                    .title(plot.getTitle() + " (第 " + (i + 1) + " 段)")
                    .summary(plot.getSummary() + " [子分段 " + (i + 1) + "]")
                    .characterRefs(plot.getCharacterRefs())
                    .locationRefs(plot.getLocationRefs())
                    .propRefs(plot.getPropRefs())
                    .timeOfDay(plot.getTimeOfDay())
                    .sceneType(plot.getSceneType())
                    .startTextOffset((plot.getStartTextOffset() != null ? plot.getStartTextOffset() : 0) + ch.getStartOffset())
                    .endTextOffset((plot.getStartTextOffset() != null ? plot.getStartTextOffset() : 0) + ch.getEndOffset())
                    .rawText(ch.getText())
                    .build();
            subPlots.add(subPlot);
        }

        return subPlots;
    }
}
