package com.astra.freyja.service;

import com.astra.freyja.dto.script.ScriptChunkVO;

import java.util.List;

/**
 * 剧本语义边界切分服务 (Stage 0)。
 * 负责将超长剧本按照影视语义边界（场景、时空、对白段、自然段）进行精准切分，并计算上下文重叠与偏移量。
 */
public interface ScriptChunkService {

    /**
     * 将长剧本文本按语义边界切分为多个连续的 ScriptChunkVO。
     *
     * @param rawText 原始长文本
     * @param maxChunkSize 单切片建议最大字数 (默认约 1500~2000 字)
     * @param overlapSize 切片间重叠字数 (默认约 100~200 字)
     * @return 结构化切片列表
     */
    List<ScriptChunkVO> splitIntoChunks(String rawText, int maxChunkSize, int overlapSize);

    /**
     * 使用系统默认推荐参数切分剧本文本。
     *
     * @param rawText 原始长文本
     * @return 结构化切片列表
     */
    List<ScriptChunkVO> splitIntoChunks(String rawText);
}
