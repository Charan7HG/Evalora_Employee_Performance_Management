package com.evalora.backend.dto;

import com.evalora.backend.entity.enums.KpiType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * HR-only KPI creation payload.
 *
 * <p>{@code active} and {@code createdBy} are deliberately absent: a new KPI is
 * always active and the creator is taken from the authenticated principal.
 * {@code description} is unbounded because the database column is {@code TEXT};
 * {@code name} and {@code category} follow their column widths.</p>
 */
public record CreateKpiRequest(
        @NotBlank(message = "KPI name is required.")
        @Size(max = 150, message = "KPI name must be at most 150 characters.")
        String name,

        String description,

        @Size(max = 80, message = "Category must be at most 80 characters.")
        String category,

        @DecimalMin(value = "0", inclusive = false,
                message = "Default weightage must be greater than 0 and at most 100.")
        @DecimalMax(value = "100",
                message = "Default weightage must be greater than 0 and at most 100.")
        @Digits(integer = 3, fraction = 2,
                message = "Default weightage must have at most 2 decimal places.")
        BigDecimal defaultWeightage,

        /** MANAGER (default) or HR. Null means MANAGER on creation. */
        KpiType kpiType) {

    /** Backwards-compatible shape: a KPI created without a type is a manager KPI. */
    public CreateKpiRequest(String name, String description, String category, BigDecimal defaultWeightage) {
        this(name, description, category, defaultWeightage, null);
    }

    /** Trims before Bean Validation, so a padded name is not treated as blank. */
    public CreateKpiRequest {
        name = name == null ? null : name.trim();
        description = trimToNull(description);
        category = trimToNull(category);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
