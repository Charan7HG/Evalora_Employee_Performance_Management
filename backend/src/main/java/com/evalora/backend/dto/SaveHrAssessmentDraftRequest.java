package com.evalora.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * HR draft payload. Each entry upserts the saved values for one assigned KPI;
 * KPIs omitted from the list keep whatever was saved before. An empty list is
 * a valid no-op draft, matching the employee and manager assessment APIs.
 */
public record SaveHrAssessmentDraftRequest(
        @NotNull(message = "HR assessment entries are required.")
        List<@Valid HrAssessmentEntryRequest> kpis) {
}
