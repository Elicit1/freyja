package com.astra.freyja.service.impl;

import com.astra.freyja.dto.script.ScriptChunkVO;
import com.astra.freyja.service.ScriptChunkService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 剧本语义边界切分服务实现。
 */
@Slf4j
@Service
public class ScriptChunkServiceImpl implements ScriptChunkService {

    private static final int DEFAULT_MAX_CHUNK_SIZE = 1500;
    private static final int DEFAULT_OVERLAP_SIZE = 150;

    // 匹配剧本中常见的章节/场次/时空/对白等强语义分割标志
    private static final Pattern STRONG_BOUNDARY_PATTERN = Pattern.compile(
            "(\\r?\\n\\s*(第[0-9一二三四五六七八九十百]+[集场卷幕节]|【[\\s\\S]*?】|场景[：:]|地点[：:]|时间[：:]|△|◆|\\n{2,}))"
    );

    // 句子级标点边界
    private static final Pattern SENTENCE_BOUNDARY_PATTERN = Pattern.compile(
            "([。！？!?](\\r?\\n|\"|”|’|\\s+|$)|\\r?\\n)"
    );

    @Override
    public List<ScriptChunkVO> splitIntoChunks(String rawText) {
        return splitIntoChunks(rawText, DEFAULT_MAX_CHUNK_SIZE, DEFAULT_OVERLAP_SIZE);
    }

    @Override
    public List<ScriptChunkVO> splitIntoChunks(String rawText, int maxChunkSize, int overlapSize) {
        if (StringUtils.isBlank(rawText)) {
            return Collections.emptyList();
        }

        String content = rawText.trim();
        int totalLen = content.length();

        // 若总长度小于或等于单个切片上限，直接作为单切片返回
        if (totalLen <= maxChunkSize) {
            ScriptChunkVO singleChunk = ScriptChunkVO.builder()
                    .chunkId("CHUNK_001")
                    .sequence(1)
                    .startOffset(0)
                    .endOffset(totalLen)
                    .text(content)
                    .overlapPrefix("")
                    .previousChunkId(null)
                    .nextChunkId(null)
                    .splitReason("SINGLE_CHUNK")
                    .build();
            return List.of(singleChunk);
        }

        List<ScriptChunkVO> chunks = new ArrayList<>();
        int currentStart = 0;
        int chunkSeq = 1;

        while (currentStart < totalLen) {
            int targetEnd = Math.min(currentStart + maxChunkSize, totalLen);

            int bestCutPoint = targetEnd;
            String splitReason = "MAX_LENGTH";

            if (targetEnd < totalLen) {
                // 在 [targetEnd - 400, targetEnd + 100] 的窗口内寻找最佳语义切分点
                int windowStart = Math.max(currentStart + (maxChunkSize / 2), targetEnd - 400);
                int windowEnd = Math.min(totalLen, targetEnd + 100);
                String searchWindow = content.substring(windowStart, windowEnd);

                // 1. 优先尝试强边界（场景、场次、双换行）
                var strongMatcher = STRONG_BOUNDARY_PATTERN.matcher(searchWindow);
                int lastStrong = -1;
                while (strongMatcher.find()) {
                    lastStrong = strongMatcher.start();
                }

                if (lastStrong != -1 && (windowStart + lastStrong) > currentStart) {
                    bestCutPoint = windowStart + lastStrong;
                    splitReason = "SCENE_OR_HEADER_BOUNDARY";
                } else {
                    // 2. 尝试句子标点与单换行边界
                    var sentMatcher = SENTENCE_BOUNDARY_PATTERN.matcher(searchWindow);
                    int lastSent = -1;
                    while (sentMatcher.find()) {
                        lastSent = sentMatcher.end();
                    }
                    if (lastSent != -1 && (windowStart + lastSent) > currentStart) {
                        bestCutPoint = windowStart + lastSent;
                        splitReason = "SENTENCE_BOUNDARY";
                    }
                }
            }

            // 提取切片内容
            String chunkText = content.substring(currentStart, bestCutPoint).trim();
            int actualEnd = bestCutPoint;

            // 计算与前一切片的重叠前缀
            String overlap = "";
            if (currentStart > 0) {
                int overlapStart = Math.max(0, currentStart - overlapSize);
                overlap = content.substring(overlapStart, currentStart).trim();
            }

            String chunkId = String.format("CHUNK_%03d", chunkSeq);
            String prevId = chunkSeq > 1 ? String.format("CHUNK_%03d", chunkSeq - 1) : null;

            ScriptChunkVO chunkVO = ScriptChunkVO.builder()
                    .chunkId(chunkId)
                    .sequence(chunkSeq)
                    .startOffset(currentStart)
                    .endOffset(actualEnd)
                    .text(chunkText)
                    .overlapPrefix(overlap)
                    .previousChunkId(prevId)
                    .splitReason(splitReason)
                    .build();

            chunks.add(chunkVO);

            if (actualEnd >= totalLen) {
                break;
            }

            // 推进游标
            currentStart = actualEnd;
            chunkSeq++;
        }

        // 补齐 nextChunkId 链
        for (int i = 0; i < chunks.size(); i++) {
            if (i < chunks.size() - 1) {
                chunks.get(i).setNextChunkId(chunks.get(i + 1).getChunkId());
            }
        }

        log.info("[ScriptChunkService] 剧本文本语义切分完成: 原始字符数={}, 切片数={}", totalLen, chunks.size());
        return chunks;
    }
}
