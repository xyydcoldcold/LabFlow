package com.labflow.backend.job;

import java.util.Set;

import tools.jackson.databind.JsonNode;

public record CreateJobRequest(
        long projectId,
        long molecularInputId,
        long experimentConfigId
) {

    private static final Set<String> FIELDS = Set.of("projectId", "molecularInputId", "experimentConfigId");

    public CreateJobRequest {
        if (projectId < 1 || molecularInputId < 1 || experimentConfigId < 1) {
            throw new IllegalArgumentException("Job request identifiers must be positive");
        }
    }

    public static CreateJobRequest from(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Job request must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unsupported job request field: " + field);
            }
        }
        return new CreateJobRequest(
                positiveLong(body, "projectId"),
                positiveLong(body, "molecularInputId"),
                positiveLong(body, "experimentConfigId")
        );
    }

    private static long positiveLong(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return value.longValue();
    }
}
