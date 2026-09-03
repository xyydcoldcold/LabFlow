package com.labflow.backend.project;

public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(long projectId) {
        super("Project " + projectId + " was not found");
    }
}
