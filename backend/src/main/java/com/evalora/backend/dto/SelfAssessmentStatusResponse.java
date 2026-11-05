package com.evalora.backend.dto;

import java.time.OffsetDateTime;

/**
 * Compact workflow view of one self-assessment: whether it is still editable,
 * how many assigned KPIs already have a rating, and when it was submitted.
 */
public record SelfAssessmentStatusResponse(
        Long assignmentId,
        Long cycleId,
        String cycleStatus,
        String assignmentStatus,
        boolean submitted,
        OffsetDateTime selfSubmittedAt,
        int totalKpis,
        int ratedKpis) {
}
