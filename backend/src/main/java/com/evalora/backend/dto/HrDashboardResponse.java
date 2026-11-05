package com.evalora.backend.dto;

public record HrDashboardResponse(
        long totalActiveEmployees,
        long totalActiveManagers,
        long totalPerformanceCycles,
        long totalPublishedCycles,
        long employeesAwaitingSelfAssessment,
        long employeesAwaitingManagerReview,
        long employeesAwaitingHrReview,
        long completedAssessments,
        long publishedResults) {
}
