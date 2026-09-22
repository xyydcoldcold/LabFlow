package com.labflow.backend.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public record JobQueuedMessage(long jobId, long eventId, int schemaVersion) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Set<String> FIELDS = Set.of("jobId", "eventId", "schemaVersion");

    public JobQueuedMessage {
        if (jobId < 1 || eventId < 1) {
            throw new IllegalArgumentException("Message identifiers must be positive");
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported job message schema version: " + schemaVersion);
        }
    }

    public static JobQueuedMessage from(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Job message must be a JSON object");
        }
        for (String field : payload.propertyNames()) {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unsupported job message field: " + field);
            }
        }
        return new JobQueuedMessage(
                positiveLong(payload, "jobId"),
                positiveLong(payload, "eventId"),
                requiredInteger(payload, "schemaVersion")
        );
    }

    public byte[] toJsonBytes() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("jobId", jobId)
                .put("eventId", eventId)
                .put("schemaVersion", schemaVersion);
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long positiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return value.longValue();
    }

    private static int requiredInteger(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }
}
