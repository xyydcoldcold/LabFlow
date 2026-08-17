package com.labflow.backend.auth;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        AuthUserResponse user
) {
    static AuthResponse from(AuthSession session) {
        return new AuthResponse(
                session.accessToken(),
                "Bearer",
                session.expiresInSeconds(),
                AuthUserResponse.from(session.user())
        );
    }
}
