package com.evalora.backend.dto;

/**
 * Minimal employee identity for the HR review screen. Deliberately excludes
 * the user's email and any account details - HR needs to know who is being
 * reviewed, not to read the employee's account.
 */
public record HrEmployeeSummary(
        Long employeeId,
        String employeeCode,
        String fullName,
        String department,
        String designation) {
}
