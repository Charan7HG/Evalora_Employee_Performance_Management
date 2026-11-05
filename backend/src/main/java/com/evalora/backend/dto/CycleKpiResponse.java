package com.evalora.backend.dto;

import java.math.BigDecimal;

/** Safe view of one KPI configured for a cycle. */
public record CycleKpiResponse(
        Long id,
        Long kpiId,
        String name,
        String description,
        String category,
        String kpiType,
        BigDecimal weightage) {
}
