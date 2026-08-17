package com.labflow.backend.auth;

public record AuthSession(AppUser user, String accessToken, long expiresInSeconds) {
}
