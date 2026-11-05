package com.evalora.backend.dto;

public record ManagerDashboardResponse(
        long totalActiveDirectReports,
        long employeesAwaitingSelfAssessment,
        long assessmentsAwaitingManagerReview,
        long assessmentsAwaitingHrReview,
        long completedAssessments,
        long publishedResults) {
}
