package com.labflow.backend.job;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(long jobId) {
        super("Job " + jobId + " was not found");
    }
}
