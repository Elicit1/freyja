package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.dashboard.DashboardStatsVO;
import com.astra.freyja.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台首页统计 Controller。
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取工作台首页全景统计数据。
     */
    @GetMapping("/stats")
    public R<DashboardStatsVO> getDashboardStats() {
        return R.ok(dashboardService.getDashboardStats());
    }
}
