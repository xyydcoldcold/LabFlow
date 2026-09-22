package com.labflow.backend.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "labflow.outbox")
public record OutboxProperties(
        int batchSize,
        Duration initialBackoff,
        Duration maxBackoff
) {

    public OutboxProperties {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("Outbox batch size must be between 1 and 1000");
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("Outbox initial backoff must be positive");
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Outbox max backoff must not be shorter than initial backoff");
        }
    }
}
