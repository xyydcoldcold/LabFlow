package com.labflow.backend.project;

import jakarta.validation.constraints.NotNull;

public record UpdateProjectMemberRoleRequest(
        @NotNull ProjectRole role
) {
}
