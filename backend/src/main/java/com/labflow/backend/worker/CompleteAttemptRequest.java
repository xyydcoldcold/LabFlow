package com.labflow.backend.worker;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record CompleteAttemptRequest(
        @NotNull JsonNode summary,
        @NotNull JsonNode manifest
) {
}
