package com.evalora.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Full replacement of a DRAFT cycle's KPI selection; an empty list clears it. */
public record ConfigureCycleKpisRequest(
        @NotNull(message = "KPI selections are required.")
        @Valid
        List<CycleKpiSelectionRequest> kpis) {
}
