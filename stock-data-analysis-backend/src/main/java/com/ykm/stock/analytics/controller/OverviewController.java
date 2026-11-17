package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse;
import com.ykm.stock.analytics.service.OverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("overview")
@RequiredArgsConstructor
public class OverviewController {

    private final OverviewService overviewService;

    @PostMapping("dashboard")
    public DashboardResponse dashboard(){
        return overviewService.getDashboard();
    }
}
