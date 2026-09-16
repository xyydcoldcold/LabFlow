package com.labflow.backend.project;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddProjectMemberRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotNull ProjectRole role
) {
    public AddProjectMemberRequest {
        email = email == null ? null : email.trim();
    }
}
