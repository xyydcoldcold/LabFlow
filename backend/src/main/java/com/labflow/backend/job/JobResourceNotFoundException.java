package com.labflow.backend.job;

public class JobResourceNotFoundException extends RuntimeException {

    public JobResourceNotFoundException() {
        super("Molecular input or experiment configuration was not found in this project");
    }
}
