package com.evalora.backend.dto;

public record EmployeeDashboardResponse(
        EmployeeCurrentCycleResponse currentCycle,
        EmployeeLatestPublishedResultResponse latestPublishedResult,
        ReportingManagerResponse reportingManager) {
}
