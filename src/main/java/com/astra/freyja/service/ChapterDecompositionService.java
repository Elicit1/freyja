package com.astra.freyja.service;

import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.PlannerDecomposeResultVO;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;

import java.util.function.Consumer;

/**
 * 章节剧情大纲与事件分段服务接口 (ChapterDecompositionService / Planner AI)。
 * 职责：仅负责整章宏观理解与戏剧事件分段 (List<StorySegment>)，严禁生成分镜镜头与运镜。
 */
public interface ChapterDecompositionService {

    /**
     * 调用 Planner AI 进行整章剧情分段与全局实体提取，并由 Java 进行段落边界严密校验与平滑对齐。
     *
     * @param chapterText 章节完整小说文本
     * @param request 拆解请求参数
     * @param globalContext 全局故事上下文
     * @param stepLogger 日志/进度输出器
     * @return Planner 结构化分段结果
     */
    PlannerDecomposeResultVO decomposeChapter(String chapterText,
                                             ScriptDecomposeRequestDTO request,
                                             GlobalStoryContext globalContext,
                                             Consumer<String> stepLogger);

    /**
     * 增强型调用：支持多通道独立 Token 直流分发，彻底杜绝正则与字符串匹配。
     */
    PlannerDecomposeResultVO decomposeChapter(String chapterText,
                                             ScriptDecomposeRequestDTO request,
                                             GlobalStoryContext globalContext,
                                             java.util.function.BiConsumer<String, String> channelChunkConsumer,
                                             Consumer<String> stepLogger);
}
