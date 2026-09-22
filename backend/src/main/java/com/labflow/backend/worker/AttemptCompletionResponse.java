package com.labflow.backend.worker;

import java.time.Instant;

public record AttemptCompletionResponse(long jobId, long attemptId, String jobStatus, Instant completedAt) {
}
