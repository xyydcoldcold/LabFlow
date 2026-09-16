package com.labflow.backend.project;

public class ProjectMemberAlreadyExistsException extends RuntimeException {

    public ProjectMemberAlreadyExistsException(long projectId) {
        super("The user is already a member of project " + projectId);
    }

    public ProjectMemberAlreadyExistsException(long projectId, Throwable cause) {
        super("The user is already a member of project " + projectId, cause);
    }
}
