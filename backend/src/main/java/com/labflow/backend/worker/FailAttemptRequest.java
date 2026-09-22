package com.labflow.backend.worker;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record FailAttemptRequest(@NotNull JsonNode error) {
}
