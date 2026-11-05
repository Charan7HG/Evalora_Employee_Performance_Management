package com.evalora.backend.controller;

import com.evalora.backend.dto.HrDashboardResponse;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.service.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/dashboard")
public class HrDashboardController {

    private final DashboardService dashboardService;

    public HrDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public HrDashboardResponse getDashboard(@AuthenticationPrincipal EvaloraPrincipal principal) {
        return dashboardService.getHrDashboard(principal.getUserId());
    }
}
