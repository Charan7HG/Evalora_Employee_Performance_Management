package com.evalora.backend.dto;

import java.math.BigDecimal;

/**
 * One frozen KPI of an assignment with all three assessment perspectives kept
 * separate: the employee's submitted self-rating (not scored), the manager's
 * rating of the KPI, and HR's rating of an HR-assessed KPI.
 *
 * <p>Manager-assessed KPIs are scored from the manager rating and contribute
 * 75 % of the overall score. HR-assessed KPIs are rated by both the manager
 * and HR; each KPI's combined rating is {@code (managerRating + hrRating) / 2}
 * and the HR category contributes 25 %. HR can never write a manager rating
 * and the manager API never writes an HR rating. No weighted or final score is
 * computed in this response; {@code weightage} is informational only.</p>
 */
public record HrAssessmentKpiResponse(
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
        String managerComment,
        Integer hrRating,
        String hrComment) {
}
