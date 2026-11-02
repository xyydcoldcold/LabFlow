package com.labflow.backend.experimentconfig;

/** Resource limits this deployment accepts for an experiment config spec. */
public record ExecutionLimits(
        int minMemoryMb,
        int maxMemoryMb,
        int minTimeoutSeconds,
        int maxTimeoutSeconds
) {
}
