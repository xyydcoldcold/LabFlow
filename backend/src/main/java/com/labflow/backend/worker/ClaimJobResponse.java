package com.labflow.backend.worker;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record ClaimJobResponse(
        long jobId,
        long attemptId,
        int attemptNo,
        String attemptToken,
        Instant leaseExpiresAt,
        String taskType,
        String inputArtifactPath,
        String inputSha256,
        JsonNode spec
) {
}
