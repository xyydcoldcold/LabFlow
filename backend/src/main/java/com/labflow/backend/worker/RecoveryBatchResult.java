package com.labflow.backend.worker;

public record RecoveryBatchResult(int claimed, int requeued, int exhausted, int cancelled, int skipped) {
}
