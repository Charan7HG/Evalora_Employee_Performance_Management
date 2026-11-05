package com.evalora.backend.dto;

import java.time.LocalDate;

public record EmployeeCurrentCycleResponse(
        Long cycleId,
        String cycleName,
        String cycleStatus,
        String assessmentStatus,
        LocalDate dueDate) {
}
