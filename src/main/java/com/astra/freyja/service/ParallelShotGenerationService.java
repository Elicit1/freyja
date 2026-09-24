package com.astra.freyja.service;

import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.dto.script.SegmentShotResult;
import com.astra.freyja.dto.script.StorySegment;

import java.util.List;
import java.util.function.Consumer;

/**
 * Worker 并行分镜生成调度服务接口 (ParallelShotGenerationService)。
 * 职责：管理 WorkerPool，通过 Semaphore 控制并发度，多线程并行调用 Worker AI 生成分镜，并支持单段隔离重试。
 */
public interface ParallelShotGenerationService {

    /**
     * 并行执行所有 StorySegment 的 Worker AI 分镜生成。
     *
     * @param segments 剧情分段列表
     * @param globalContext 全局故事上下文
     * @param request 整体请求参数
     * @param stepLogger 日志输出器
     * @param segmentProgressCallback 单段状态变更回调 (用于前端实时展示各段进度)
     * @return 各分段生成的镜头结果列表 (已按 segment.sequence 排序)
     */
    List<SegmentShotResult> generateShotsInParallel(List<StorySegment> segments,
                                                   GlobalStoryContext globalContext,
                                                   ScriptDecomposeRequestDTO request,
                                                   Consumer<String> stepLogger,
                                                   Consumer<SegmentShotResult> segmentProgressCallback);

    /**
     * 增强型并行调用：支持多通道独立 Token 直流分发，彻底杜绝正则与字符串匹配。
     */
    List<SegmentShotResult> generateShotsInParallel(List<StorySegment> segments,
                                                   GlobalStoryContext globalContext,
                                                   ScriptDecomposeRequestDTO request,
                                                   java.util.function.BiConsumer<String, String> channelChunkConsumer,
                                                   Consumer<String> stepLogger,
                                                   Consumer<SegmentShotResult> segmentProgressCallback);

    /**
     * 单独重试指定失败的 Segment。
     */
    SegmentShotResult retrySingleSegment(StorySegment segment,
                                         GlobalStoryContext globalContext,
                                         String previousContext,
                                         String nextContext,
                                         ScriptDecomposeRequestDTO request,
                                         Consumer<String> stepLogger);

    /**
     * 单独重试指定失败的 Segment (支持自定义指导提示词/避险词)。
     */
    SegmentShotResult retrySingleSegment(StorySegment segment,
                                         GlobalStoryContext globalContext,
                                         String previousContext,
                                         String nextContext,
                                         ScriptDecomposeRequestDTO request,
                                         String customInstructions,
                                         Consumer<String> stepLogger);
}
