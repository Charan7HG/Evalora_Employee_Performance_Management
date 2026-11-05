package com.evalora.backend.dto;

/**
 * Safe HR view of one employee configured for a cycle. {@code id} is the
 * {@code employee_assignments} row id; the status is the workflow enum name.
 */
public record CycleEmployeeResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        Long managerId,
        String managerName,
        String status) {
}
