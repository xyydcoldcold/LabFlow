package com.labflow.backend.worker;

import java.time.Instant;

public record AttemptHeartbeatResponse(long attemptId, Instant leaseExpiresAt, boolean cancelRequested) {
}
