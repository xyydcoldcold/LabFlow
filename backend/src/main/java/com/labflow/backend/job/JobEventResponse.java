package com.labflow.backend.job;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record JobEventResponse(long id, String fromStatus, String toStatus,
                               String eventType, JsonNode details, Instant createdAt) {
}
