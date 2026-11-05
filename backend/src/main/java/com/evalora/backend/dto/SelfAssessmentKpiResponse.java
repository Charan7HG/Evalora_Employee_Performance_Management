package com.evalora.backend.dto;

import java.math.BigDecimal;

/**
 * One frozen KPI of an assignment together with the employee's own draft.
 * {@code employeeKpiId} is the id the draft and submit calls use; it is scoped
 * to this employee's assignment and never accepted from another source.
 */
public record SelfAssessmentKpiResponse(
        Long employeeKpiId,
        Long kpiId,
        String name,
        String description,
        String category,
        String kpiType,
        BigDecimal weightage,
        Integer rating,
        String comment) {
}
