package com.evalora.backend.controller;

import com.evalora.backend.dto.ManagerDashboardResponse;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.service.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/dashboard")
public class ManagerDashboardController {

    private final DashboardService dashboardService;

    public ManagerDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ManagerDashboardResponse getDashboard(@AuthenticationPrincipal EvaloraPrincipal principal) {
        return dashboardService.getManagerDashboard(principal.getUserId());
    }
}
