package com.labflow.backend.worker;

public class WorkerNotFoundException extends RuntimeException {
    public WorkerNotFoundException(long workerId) {
        super("Worker " + workerId + " was not found");
    }
}
