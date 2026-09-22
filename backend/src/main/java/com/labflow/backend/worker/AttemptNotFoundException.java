package com.labflow.backend.worker;

public class AttemptNotFoundException extends RuntimeException {
    public AttemptNotFoundException(long attemptId) {
        super("Attempt " + attemptId + " was not found");
    }
}
