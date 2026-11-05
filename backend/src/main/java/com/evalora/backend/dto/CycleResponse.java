package com.evalora.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Safe HR view of a performance cycle. Status is the enum name
 * ({@code DRAFT}, {@code PUBLISHED}, {@code IN_PROGRESS}, {@code COMPLETED});
 * no JPA entity or relationship graph is exposed.
 */
public record CycleResponse(
        Long id,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate selfAssessmentDeadline,
        LocalDate managerReviewDeadline,
        LocalDate hrReviewDeadline,
        String status,
        Long createdById,
        OffsetDateTime publishedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
