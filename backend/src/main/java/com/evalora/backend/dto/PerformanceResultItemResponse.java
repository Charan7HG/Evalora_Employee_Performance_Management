package com.evalora.backend.dto;

import java.math.BigDecimal;

public record PerformanceResultItemResponse(
        Long employeeKpiId,
        String kpiName,
        BigDecimal weightage,
        Short selfRating,
        String selfComment,
        Short managerRating,
        String managerComment,
        Short hrRating,
        String hrComment,
        BigDecimal finalScore) {
}
