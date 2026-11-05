package com.evalora.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Draft payload. Each entry upserts the saved values for one assigned KPI;
 * KPIs omitted from the list keep whatever was saved before. An empty list is
 * a valid no-op draft.
 */
public record SaveSelfAssessmentDraftRequest(
        @NotNull(message = "Self-assessment entries are required.")
        List<@Valid SelfAssessmentEntryRequest> kpis) {
}
