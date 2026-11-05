package com.evalora.backend.dto;

import java.time.OffsetDateTime;

/**
 * Compact workflow view of one HR assessment: whether it has been submitted,
 * how many HR-assessed KPIs already have an HR rating, and when it was
 * finalized. Manager-assessed KPIs are not part of HR's progress count.
 */
public record HrAssessmentStatusResponse(
        Long assignmentId,
        Long cycleId,
        String assignmentStatus,
        int totalKpis,
        int ratedKpis,
        boolean submitted,
        OffsetDateTime hrFinalizedAt) {
}
