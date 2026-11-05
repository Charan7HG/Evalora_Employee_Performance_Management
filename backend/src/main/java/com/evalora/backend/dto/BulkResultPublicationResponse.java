package com.evalora.backend.dto;

import java.util.List;

public record BulkResultPublicationResponse(
        Long cycleId,
        int publishedCount,
        int skippedCount,
        List<Long> publishedResultIds) {
}
