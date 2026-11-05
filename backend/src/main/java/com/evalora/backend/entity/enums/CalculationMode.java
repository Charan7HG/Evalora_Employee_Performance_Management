package com.evalora.backend.entity.enums;

/**
 * WEIGHTED = a weightage is configured for every KPI of the assignment.
 * AVERAGE  = no weightage is configured at all.
 * Mixing both is never allowed.
 */
public enum CalculationMode {
    WEIGHTED,
    AVERAGE
}
