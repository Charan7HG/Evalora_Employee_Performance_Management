package com.evalora.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Full replacement of a DRAFT cycle's employee roster; an empty list clears it. */
public record ConfigureCycleEmployeesRequest(
        @NotNull(message = "Employee assignments are required.")
        @Valid
        List<CycleEmployeeSelectionRequest> employees) {
}
