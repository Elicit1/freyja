package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分发给单个 Shot Worker 的完整上下文 (WorkerSegmentContext)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkerSegmentContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 全局上下文缓存 (Global Story Context) */
    private GlobalStoryContext globalContext;

    /** 当前需处理的剧情分段 (Story Segment) */
    private StorySegment currentSegment;

    /** 前一个 Segment 的轻量上下文 (由 Java 从上一段的最后状态计算) */
    private String previousContext;

    /** 后一个 Segment 的轻量上下文 (概要信息) */
    private String nextContext;

    /** 视觉风格基调/导演风格指南（自然语言融入，如光影、色调、镜头质感） */
    private String styleTone;
}
