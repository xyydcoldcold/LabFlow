package com.labflow.backend.experimentconfig;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record ExperimentConfigResponse(
        long id,
        String name,
        int version,
        JsonNode spec,
        long createdBy,
        Instant createdAt
) {
    static ExperimentConfigResponse from(ExperimentConfig config) {
        return new ExperimentConfigResponse(
                config.getId(),
                config.getName(),
                config.getVersion(),
                config.getSpec(),
                config.getCreatedBy().getId(),
                config.getCreatedAt()
        );
    }
}
