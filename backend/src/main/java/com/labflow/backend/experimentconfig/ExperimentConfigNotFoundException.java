package com.labflow.backend.experimentconfig;

public class ExperimentConfigNotFoundException extends RuntimeException {

    public ExperimentConfigNotFoundException(long projectId, long configId) {
        super("Experiment config " + configId + " was not found in project " + projectId);
    }
}
