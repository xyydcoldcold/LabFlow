package com.labflow.backend.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "labflow.recovery")
public record RecoveryProperties(int batchSize) {

    public RecoveryProperties {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("Recovery batch size must be between 1 and 1000");
        }
    }
}
