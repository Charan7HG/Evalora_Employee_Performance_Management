package com.evalora.backend.dto;

import java.time.OffsetDateTime;

/**
 * Compact workflow view of one manager assessment: whether it is still
 * editable, how many assigned KPIs already have a manager rating, and when it
 * was submitted.
 */
public record ManagerAssessmentStatusResponse(
        Long assignmentId,
        Long cycleId,
        String assignmentStatus,
        int totalKpis,
        int ratedKpis,
        boolean submitted,
        OffsetDateTime managerSubmittedAt) {
}
