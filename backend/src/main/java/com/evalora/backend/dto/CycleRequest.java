package com.evalora.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * HR-only cycle create/update payload.
 *
 * <p>Status, publication timestamps, creator and audit fields are deliberately
 * absent: a new cycle is always DRAFT, and only the dedicated publish operation
 * changes its lifecycle.</p>
 */
public record CycleRequest(
        @NotBlank(message = "Cycle name is required.")
        @Size(max = 150, message = "Cycle name must be at most 150 characters.")
        String name,

        String description,

        @NotNull(message = "Start date is required.")
        LocalDate startDate,

        @NotNull(message = "End date is required.")
        LocalDate endDate,

        @NotNull(message = "Self-assessment deadline is required.")
        LocalDate selfAssessmentDeadline,

        @NotNull(message = "Manager review deadline is required.")
        LocalDate managerReviewDeadline,

        @NotNull(message = "HR review deadline is required.")
        LocalDate hrReviewDeadline) {

    /** Trims before Bean Validation so a padded name is not treated as blank. */
    public CycleRequest {
        name = name == null ? null : name.trim();
        description = trimToNull(description);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
