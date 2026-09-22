package com.labflow.backend.worker;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "labflow.worker")
public record WorkerProperties(String serviceToken, Duration attemptLease) {

    public WorkerProperties {
        Objects.requireNonNull(serviceToken, "Worker service token is required");
        Objects.requireNonNull(attemptLease, "Worker attempt lease is required");
        if (serviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Worker service token must contain at least 32 UTF-8 bytes");
        }
        if (attemptLease.isZero() || attemptLease.isNegative()) {
            throw new IllegalArgumentException("Worker attempt lease must be positive");
        }
    }
}
