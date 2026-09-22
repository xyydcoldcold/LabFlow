package com.labflow.backend.worker;

public class StaleAttemptException extends RuntimeException {
    public StaleAttemptException(long attemptId) {
        super("Attempt " + attemptId + " is no longer authorized to update its job");
    }
}
