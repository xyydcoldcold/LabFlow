package com.labflow.backend.worker;

public class JobNotClaimableException extends RuntimeException {
    public JobNotClaimableException(long jobId) {
        super("Job " + jobId + " is not claimable");
    }
}
