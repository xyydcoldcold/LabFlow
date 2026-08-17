package com.labflow.backend.auth;

import java.time.Instant;

public record AuthUserResponse(
        long id,
        String email,
        String displayName,
        Instant createdAt
) {
    static AuthUserResponse from(AppUser user) {
        return new AuthUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getCreatedAt()
        );
    }
}
