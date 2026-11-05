package com.evalora.backend.controller;

import com.evalora.backend.dto.EmployeeDashboardResponse;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.service.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employee/dashboard")
public class EmployeeDashboardController {

    private final DashboardService dashboardService;

    public EmployeeDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public EmployeeDashboardResponse getDashboard(@AuthenticationPrincipal EvaloraPrincipal principal) {
        return dashboardService.getEmployeeDashboard(principal.getUserId());
    }
}
