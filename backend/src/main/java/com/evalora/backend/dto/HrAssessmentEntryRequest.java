package com.evalora.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One KPI entry of an HR assessment draft.
 *
 * <p>As in the employee and manager APIs, {@code rating} is a
 * {@link BigDecimal} rather than an {@code Integer} so that {@code 3.5} is
 * rejected by the service instead of being silently truncated to {@code 3}.</p>
 *
 * <p>Both fields are optional while drafting. Submission requires a rating for
 * every assigned KPI.</p>
 */
public record HrAssessmentEntryRequest(
        @NotNull(message = "Employee KPI id is required.")
        Long employeeKpiId,

        BigDecimal rating,

        String comment) {
}
