package com.evalora.backend.dto;

import com.evalora.backend.entity.enums.KpiType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * HR-only KPI update payload. Only editable catalogue fields are accepted;
 * {@code id}, {@code active}, {@code createdBy} and timestamps cannot be
 * changed here (state changes use the dedicated deactivate/reactivate calls).
 */
public record UpdateKpiRequest(
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

        /** MANAGER or HR. Null keeps the stored classification unchanged. */
        KpiType kpiType) {

    /** Backwards-compatible shape: the stored classification is kept. */
    public UpdateKpiRequest(String name, String description, String category, BigDecimal defaultWeightage) {
        this(name, description, category, defaultWeightage, null);
    }

    /** Trims before Bean Validation, for consistency with creation. */
    public UpdateKpiRequest {
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
