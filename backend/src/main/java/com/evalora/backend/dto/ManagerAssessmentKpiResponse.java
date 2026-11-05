package com.evalora.backend.dto;

import java.math.BigDecimal;

/**
 * One frozen KPI of an assignment with both sides of the review: the
 * employee's submitted self-assessment and the manager's current draft or
 * submitted rating.
 *
 * <p>{@code selfRating}/{@code selfComment} are read-only here; the manager API
 * never writes them. Weightage is informational only - no score is computed in
 * this phase.</p>
 */
public record ManagerAssessmentKpiResponse(
        Long employeeKpiId,
        Long kpiId,
        String name,
        String description,
        String category,
        String kpiType,
        BigDecimal weightage,
        Integer selfRating,
        String selfComment,
        Integer managerRating,
        String managerComment) {
}
