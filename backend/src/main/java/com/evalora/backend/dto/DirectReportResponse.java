package com.evalora.backend.dto;

/**
 * One active direct report of the authenticated manager. Used by the manager
 * dashboard's "Active direct reports" widget destination.
 */
public record DirectReportResponse(
        Long employeeId,
        String employeeCode,
        String fullName,
        String email,
        String department,
        String designation,
        String status) {
}
