package com.labflow.backend.experimentconfig;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record CreateExperimentConfigRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull JsonNode spec
) {
    public CreateExperimentConfigRequest {
        name = name == null ? null : name.trim();
    }
}
