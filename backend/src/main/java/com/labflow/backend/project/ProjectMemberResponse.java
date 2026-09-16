package com.labflow.backend.project;

import java.time.Instant;

public record ProjectMemberResponse(
        ProjectUserResponse user,
        ProjectRole role,
        Instant joinedAt,
        Instant updatedAt
) {
    static ProjectMemberResponse from(ProjectMember membership) {
        return new ProjectMemberResponse(
                ProjectUserResponse.from(membership.getUser()),
                membership.getRole(),
                membership.getCreatedAt(),
                membership.getUpdatedAt()
        );
    }
}
