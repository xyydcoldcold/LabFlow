package com.labflow.backend.worker;

import java.util.Set;

public final class FailurePolicy {
    private static final Set<String> TRANSIENT = Set.of(
            "LEASE_EXPIRED", "NETWORK_ERROR", "LOG_UPLOAD_FAILED", "TRANSIENT_TASK_ERROR");
    private FailurePolicy() { }
    public static boolean retryable(String code) { return TRANSIENT.contains(code); }
    public static int delaySeconds(int failedAttempt) {
        return failedAttempt == 1 ? 15 : failedAttempt == 2 ? 60 : 300;
    }
}
