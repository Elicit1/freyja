package com.astra.freyja.service;

import com.astra.freyja.dto.dashboard.DashboardStatsVO;

/**
 * 工作台首页统计服务接口。
 */
public interface DashboardService {

    /**
     * 获取全景统计看板数据。
     */
    DashboardStatsVO getDashboardStats();
}
