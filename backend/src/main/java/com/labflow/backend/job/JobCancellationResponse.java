package com.labflow.backend.job;

import java.time.Instant;

public record JobCancellationResponse(long jobId, JobState status, Instant cancelRequestedAt) {
}
