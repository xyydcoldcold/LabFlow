package com.labflow.backend.worker;

public class AttemptCancellationRequestedException extends RuntimeException {
    public AttemptCancellationRequestedException(long attemptId) {
        super("Cancellation was requested for attempt " + attemptId);
    }
}
