package com.evalora.backend.dto;

/**
 * The employee's current reporting manager.
 *
 * <p>Only the manager's identity is exposed - no email, account state or other
 * employee details. {@code managerId} and {@code employeeCode} are null when
 * the employee has no assigned manager.</p>
 */
public record ReportingManagerResponse(
        Long managerId,
        String managerName,
        String employeeCode) {

    public static ReportingManagerResponse none() {
        return new ReportingManagerResponse(null, null, null);
    }
}
