package com.evalora.backend.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record PerformanceResultResponse(
        Long resultId,
        Long assignmentId,
        PerformanceResultEmployeeResponse employee,
        PerformanceResultCycleResponse cycle,
        BigDecimal overallScore,
        String grade,
        String calculationMode,
        String status,
        OffsetDateTime hrFinalizedAt,
        OffsetDateTime publishedAt,
        List<PerformanceResultItemResponse> items) {
}
