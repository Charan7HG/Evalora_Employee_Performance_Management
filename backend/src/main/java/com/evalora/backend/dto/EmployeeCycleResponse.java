package com.evalora.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One published cycle as seen by the assigned employee. {@code assignmentId}
 * is the id used by every self-assessment endpoint; the cycle id is included
 * for display only.
 */
public record EmployeeCycleResponse(
        Long assignmentId,
        Long cycleId,
        String cycleName,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate selfAssessmentDeadline,
        LocalDate managerReviewDeadline,
        LocalDate hrReviewDeadline,
        String cycleStatus,
        OffsetDateTime publishedAt,
        String assignmentStatus,
        OffsetDateTime selfSubmittedAt) {
}
