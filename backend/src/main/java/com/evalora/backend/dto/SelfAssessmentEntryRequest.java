package com.evalora.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One KPI entry of an employee's self-assessment draft.
 *
 * <p>{@code rating} is deliberately a {@link BigDecimal} rather than an
 * {@code Integer}: the JSON layer would otherwise accept {@code 3.5} and
 * silently truncate it to {@code 3}. The service rejects any non-integral
 * value, so a decimal rating is a 400 instead of a corrupted rating.</p>
 *
 * <p>Both {@code rating} and {@code comment} are optional while drafting.
 * Submission requires a rating for every assigned KPI.</p>
 */
public record SelfAssessmentEntryRequest(
        @NotNull(message = "Employee KPI id is required.")
        Long employeeKpiId,

        BigDecimal rating,

        String comment) {
}
