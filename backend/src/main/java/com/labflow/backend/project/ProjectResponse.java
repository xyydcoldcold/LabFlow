package com.labflow.backend.project;

import java.time.Instant;

public record ProjectResponse(
        long id,
        String name,
        ProjectUserResponse owner,
        ProjectRole currentUserRole,
        Instant createdAt,
        Instant updatedAt
) {
    static ProjectResponse from(Project project, ProjectRole currentUserRole) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                ProjectUserResponse.from(project.getOwner()),
                currentUserRole,
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
