package com.evalora.backend.dto;

import java.time.LocalDate;

public record PerformanceResultCycleResponse(
        Long cycleId,
        String cycleName,
        LocalDate startDate,
        LocalDate endDate) {
}
