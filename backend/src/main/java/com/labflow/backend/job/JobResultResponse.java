package com.labflow.backend.job;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record JobResultResponse(
        long attemptId,
        JsonNode summary,
        JsonNode manifest,
        Instant completedAt
) {
}
