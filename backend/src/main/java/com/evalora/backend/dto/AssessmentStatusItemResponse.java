package com.evalora.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One assignment row for a dashboard assessment-status list. The same shape is
 * returned to HR (organization-wide) and to a manager (their own direct
 * reports only), so the two dashboard counters and their destination lists are
 * driven by one query and one response.
 *
 * <p>{@code dueDate} is the deadline that belongs to the current status:
 * self-assessment, manager review or HR review. It is null for a completed
 * assessment because no further action is due.</p>
 */
public record AssessmentStatusItemResponse(
        Long assignmentId,
        Long cycleId,
        String cycleName,
        String cycleStatus,
        String assignmentStatus,
        Long employeeId,
        String employeeCode,
        String employeeFullName,
        String department,
        String designation,
        Long managerId,
        String managerName,
        OffsetDateTime selfSubmittedAt,
        OffsetDateTime managerSubmittedAt,
        OffsetDateTime hrFinalizedAt,
        LocalDate dueDate) {
}
