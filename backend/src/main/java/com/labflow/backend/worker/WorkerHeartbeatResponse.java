package com.labflow.backend.worker;

import java.time.Instant;

public record WorkerHeartbeatResponse(long workerId, Instant lastHeartbeatAt) {
}
