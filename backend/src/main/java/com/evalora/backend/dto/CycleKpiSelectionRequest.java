package com.evalora.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One KPI selected for a cycle. {@code weightage} may be null; a cycle is
 * either entirely unweighted (average mode) or entirely weighted.
 */
public record CycleKpiSelectionRequest(
        @NotNull(message = "KPI id is required.")
        Long kpiId,

        @DecimalMin(value = "0", inclusive = false,
                message = "KPI weightage must be greater than 0 and at most 100.")
        @DecimalMax(value = "100",
                message = "KPI weightage must be greater than 0 and at most 100.")
        @Digits(integer = 3, fraction = 2,
                message = "KPI weightage must have at most 2 decimal places.")
        BigDecimal weightage) {
}
