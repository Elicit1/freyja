package com.astra.freyja.dto.script;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 剧本文本语义切分块 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptChunkVO implements Serializable {

    /** 切片唯一ID (如 CHUNK_001) */
    private String chunkId;

    /** 顺序号 (1, 2, 3...) */
    private Integer sequence;

    /** 在原始全文中的起始字符偏移 */
    private Integer startOffset;

    /** 在原始全文中的结束字符偏移 */
    private Integer endOffset;

    /** 切片核心正文内容 */
    private String text;

    /** 与前一切片的重叠上下文文本 (用于上下文承接) */
    private String overlapPrefix;

    /** 前一切片 ID */
    private String previousChunkId;

    /** 后一切片 ID */
    private String nextChunkId;

    /** 切分边界原因 (如 SCENE_CHANGE, TIME_CHANGE, DIALOGUE_BREAK, PARAGRAPH_BREAK) */
    private String splitReason;
}
