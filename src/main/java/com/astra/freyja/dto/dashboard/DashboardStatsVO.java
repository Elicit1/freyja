package com.astra.freyja.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 工作台首页全景统计看板 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsVO implements Serializable {

    /** 剧作统计 */
    private Long dramaCount;
    private Long episodeCount;
    private Long sceneCount;
    private Long shotCount;

    /** 资产统计 */
    private Long characterCount;
    private Long sceneAssetCount;
    private Long propCount;
    private Long totalAssetCount;

    /** 渲染生成产物统计 */
    private Long renderedVideoCount;
    private Long renderedImageCount;

    /** AI 与系统服务状态 */
    private Long aiProviderCount;
    private Long aiTaskCount;
    private Boolean aiServiceReady;
    private Boolean storageReady;

    /** 题材分布 (题材Code/名称 -> 数量) */
    private Map<String, Long> genreDistribution;

    /** 最近活跃的短剧项目列表 (前 5~6 条) */
    private List<RecentDramaVO> recentDramas;
}
