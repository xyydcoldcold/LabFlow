package com.labflow.backend.outbox;

public record OutboxBatchResult(int claimed, int published, int failed) {
}
