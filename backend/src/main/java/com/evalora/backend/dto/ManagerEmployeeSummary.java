package com.evalora.backend.dto;

/**
 * Minimal employee identity for the manager review screen. Deliberately
 * excludes the user's email and any account details - the manager needs to
 * know who they are reviewing, not to read the employee's profile.
 */
public record ManagerEmployeeSummary(
        Long employeeId,
        String employeeCode,
        String fullName,
        String department,
        String designation) {
}
