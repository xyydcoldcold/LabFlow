package com.labflow.backend.job;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record JobAttemptResponse(
        long id,
        int attemptNo,
        String status,
        long workerId,
        String workerInstance,
        Instant startedAt,
        Instant finishedAt,
        JsonNode failure
) {
}
