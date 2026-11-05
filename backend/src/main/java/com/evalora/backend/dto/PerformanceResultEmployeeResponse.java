package com.evalora.backend.dto;

public record PerformanceResultEmployeeResponse(
        Long employeeId,
        String employeeCode,
        String fullName,
        String department,
        String designation) {
}
