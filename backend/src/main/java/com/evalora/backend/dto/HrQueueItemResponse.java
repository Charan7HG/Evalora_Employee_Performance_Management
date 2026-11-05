package com.evalora.backend.dto;

import java.time.OffsetDateTime;

/**
 * One row of the HR pending-review queue. {@code assignmentId} is the id used
 * by every HR assessment endpoint.
 */
public record HrQueueItemResponse(
        Long assignmentId,
        Long employeeId,
        String employeeCode,
        String employeeName,
        String department,
        String designation,
        Long cycleId,
        String cycleName,
        OffsetDateTime managerSubmittedAt,
        String assignmentStatus) {
}
