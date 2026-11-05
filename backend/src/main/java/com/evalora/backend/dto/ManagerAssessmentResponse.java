package com.evalora.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The manager's review screen for one assignment: cycle header, minimal
 * employee identity, frozen KPI snapshots with the employee's submitted
 * self-rating and the manager's saved rating.
 *
 * <p>{@code editable} is true only while the assignment is in
 * {@code MANAGER_REVIEW_PENDING}. Weightage is informational; the manager
 * rating contributes 75% of a score that this phase does not compute.</p>
 */
public record ManagerAssessmentResponse(
        Long assignmentId,
        Long cycleId,
        String cycleName,
        String cycleStatus,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate managerReviewDeadline,
        String assignmentStatus,
        OffsetDateTime selfSubmittedAt,
        OffsetDateTime managerSubmittedAt,
        boolean editable,
        ManagerEmployeeSummary employee,
        List<ManagerAssessmentKpiResponse> kpis) {
}
