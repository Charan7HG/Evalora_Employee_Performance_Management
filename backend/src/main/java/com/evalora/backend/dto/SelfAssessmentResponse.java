package com.evalora.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The employee's view of one published assignment: the cycle header and every
 * frozen KPI with the currently saved self-assessment values.
 *
 * <p>{@code editable} is false once the self-assessment has been submitted.
 * Weightage is shown for information only; the self-rating contributes 0% to
 * the final score.</p>
 */
public record SelfAssessmentResponse(
        Long assignmentId,
        Long cycleId,
        String cycleName,
        String cycleStatus,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate selfAssessmentDeadline,
        String assignmentStatus,
        OffsetDateTime selfSubmittedAt,
        boolean editable,
        List<SelfAssessmentKpiResponse> kpis) {
}
