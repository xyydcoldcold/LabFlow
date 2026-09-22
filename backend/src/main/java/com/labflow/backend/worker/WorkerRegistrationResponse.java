package com.labflow.backend.worker;

import java.time.Instant;
import java.util.List;

public record WorkerRegistrationResponse(
        long workerId,
        String instanceName,
        String imageDigest,
        List<String> capabilities,
        Instant registeredAt
) {
}
