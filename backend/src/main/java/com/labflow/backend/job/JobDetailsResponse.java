package com.labflow.backend.job;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

public record JobDetailsResponse(
        long id,
        long projectId,
        long molecularInputId,
        long experimentConfigId,
        JobState status,
        JsonNode specSnapshot,
        Instant createdAt,
        Instant updatedAt,
        List<JobAttemptResponse> attempts,
        List<JobLogChunkResponse> logs,
        JobResultResponse result
) {
}
