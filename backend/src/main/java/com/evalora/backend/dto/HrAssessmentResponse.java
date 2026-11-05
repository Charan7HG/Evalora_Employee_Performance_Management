package com.evalora.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The HR review screen for one assignment: cycle header, minimal employee
 * identity, frozen KPI snapshots with the employee's self-rating, the
 * manager's rating and HR's saved rating.
 *
 * <p>{@code editable} is true only while the assignment is in
 * {@code HR_REVIEW_PENDING}. The response contains no computed score - Phase
 * 3G only stores HR ratings and comments.</p>
 */
public record HrAssessmentResponse(
        Long assignmentId,
        Long cycleId,
        String cycleName,
        String cycleStatus,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate hrReviewDeadline,
        String assignmentStatus,
        OffsetDateTime selfSubmittedAt,
        OffsetDateTime managerSubmittedAt,
        OffsetDateTime hrFinalizedAt,
        boolean editable,
        HrEmployeeSummary employee,
        List<HrAssessmentKpiResponse> kpis) {
}
