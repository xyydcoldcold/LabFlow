package com.labflow.backend.job;

public class JobNotCancellableException extends RuntimeException {
    public JobNotCancellableException(long jobId) {
        super("Job " + jobId + " has already finished and cannot be cancelled");
    }
}
