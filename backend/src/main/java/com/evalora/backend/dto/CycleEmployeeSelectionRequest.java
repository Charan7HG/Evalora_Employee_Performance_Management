package com.evalora.backend.dto;

import jakarta.validation.constraints.NotNull;

/**
 * One employee in a DRAFT cycle roster. {@code managerId} is optional: when
 * omitted, the employee's current reporting manager is used.
 */
public record CycleEmployeeSelectionRequest(
        @NotNull(message = "Employee id is required.")
        Long employeeId,

        Long managerId) {
}
