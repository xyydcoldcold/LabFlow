package com.labflow.backend.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(max = 200) String name
) {
    public CreateProjectRequest {
        name = name == null ? null : name.trim();
    }
}
