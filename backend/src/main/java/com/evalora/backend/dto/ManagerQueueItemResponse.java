package com.evalora.backend.dto;

import java.time.OffsetDateTime;

/**
 * One row of the manager's pending-review queue. {@code assignmentId} is the
 * id used by every manager assessment endpoint.
 */
public record ManagerQueueItemResponse(
        Long assignmentId,
        Long employeeId,
        String employeeCode,
        String employeeName,
        Long cycleId,
        String cycleName,
        OffsetDateTime selfSubmittedAt,
        String assignmentStatus) {
}
