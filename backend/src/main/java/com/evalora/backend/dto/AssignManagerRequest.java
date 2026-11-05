package com.evalora.backend.dto;

import jakarta.validation.constraints.NotNull;

/** HR assignment/reassignment of an employee's reporting manager. */
public record AssignManagerRequest(
        @NotNull(message = "Manager id is required.")
        Long managerId) {
}
