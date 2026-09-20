package com.labflow.backend.job;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was already used for a different job request");
    }
}
