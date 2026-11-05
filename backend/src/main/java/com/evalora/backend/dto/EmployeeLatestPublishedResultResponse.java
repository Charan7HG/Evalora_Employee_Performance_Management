package com.evalora.backend.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record EmployeeLatestPublishedResultResponse(
        BigDecimal score,
        OffsetDateTime publishedAt,
        PerformanceResultCycleResponse cycle) {
}
