package com.evalora.backend.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Safe HR view of a KPI. Exposes no JPA relationship graph and no security
 * data; {@code createdById} is the HR user id taken from the database.
 */
public record KpiResponse(
        Long id,
        String name,
        String description,
        String category,
        String kpiType,
        BigDecimal defaultWeightage,
        boolean active,
        Long createdById,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
