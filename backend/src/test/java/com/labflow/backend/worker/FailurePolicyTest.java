package com.labflow.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FailurePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"LEASE_EXPIRED", "NETWORK_ERROR", "LOG_UPLOAD_FAILED", "TRANSIENT_TASK_ERROR"})
    void retriesOnlyKnownTransientFailures(String code) {
        assertThat(FailurePolicy.retryable(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCF_NOT_CONVERGED", "INVALID_INPUT", "TIMEOUT", "UNKNOWN_ERROR", "network_error", ""})
    void permanentAndUnknownFailuresDoNotConsumeAnotherAttempt(String code) {
        assertThat(FailurePolicy.retryable(code)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1,15", "2,60", "3,300", "4,300", "2147483647,300"})
    void retryDelayUsesBoundedBrokerTiers(int attempt, int seconds) {
        assertThat(FailurePolicy.delaySeconds(attempt)).isEqualTo(seconds);
    }
}
