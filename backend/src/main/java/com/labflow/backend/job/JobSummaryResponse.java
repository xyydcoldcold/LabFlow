package com.labflow.backend.job;

import java.time.Instant;

public record JobSummaryResponse(
        long id,
        long projectId,
        long molecularInputId,
        long experimentConfigId,
        JobState status,
        Instant createdAt,
        Instant updatedAt
) {
}
