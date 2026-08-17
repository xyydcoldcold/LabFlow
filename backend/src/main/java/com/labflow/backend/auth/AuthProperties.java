package com.labflow.backend.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "labflow.auth.jwt")
public record AuthProperties(
        String secret,
        String issuer,
        Duration ttl
) {
}
