package com.astra.freyja.service;

import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.dto.script.StorySegment;
import java.util.function.Consumer;

/** 标准化单个 Planner 分段；失败时返回原文并保留失败日志。 */
public interface StoryNormalizerService {
    void normalize(StorySegment segment, GlobalStoryContext context, ScriptDecomposeRequestDTO request);

    /** 将模型的原始流式片元推送到现有任务消息总线。 */
    default void normalize(StorySegment segment, GlobalStoryContext context,
                           ScriptDecomposeRequestDTO request, Consumer<String> chunkConsumer) {
        normalize(segment, context, request);
    }
}
