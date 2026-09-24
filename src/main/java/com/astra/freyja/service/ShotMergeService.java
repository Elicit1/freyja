package com.astra.freyja.service;

import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.PlannerDecomposeResultVO;
import com.astra.freyja.dto.script.ScriptDecomposeResultVO;
import com.astra.freyja.dto.script.SegmentShotResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * 分镜合并引擎服务接口 (ShotMergeService)。
 * 职责：纯 Java 本地将并行 Worker 产生的局部分镜结果按 Sequence 排序、合并 Scene 与 ShotGroup、全局重编 Shot 序号与 ID。
 */
public interface ShotMergeService {

    /**
     * 将多个 Segment 的 Worker 分镜结果合并为统一的剧本拆解总结果 VO。
     *
     * @param segmentResults 并行 Worker 输出列表
     * @param plannerResult Planner 规划结果
     * @param globalContext 全局故事上下文
     * @param stepLogger 日志输出器
     * @return 合并后的完整结构化结果
     */
    /**
     * 将多个 Segment 的 Worker 分镜结果合并为统一的剧本拆解总结果 VO。
     *
     * @param segmentResults 并行 Worker 输出列表
     * @param plannerResult Planner 规划结果
     * @param globalContext 全局故事上下文
     * @param stepLogger 日志输出器
     * @return 合并后的完整结构化结果
     */
    ScriptDecomposeResultVO mergeSegmentResults(List<SegmentShotResult> segmentResults,
                                               PlannerDecomposeResultVO plannerResult,
                                               GlobalStoryContext globalContext,
                                               Consumer<String> stepLogger);

    /**
     * 计算分镜时长分布与镜头碎片率统计指标 (0 次 AI 调用)
     *
     * @param shots 全部分镜列表
     * @return 碎片率统计结果
     */
    com.astra.freyja.dto.script.FragmentationStatsVO calculateFragmentationStats(List<com.astra.freyja.dto.script.DecomposedShotVO> shots);
}
