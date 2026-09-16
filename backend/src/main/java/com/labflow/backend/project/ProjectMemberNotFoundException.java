package com.labflow.backend.project;

public class ProjectMemberNotFoundException extends RuntimeException {

    public ProjectMemberNotFoundException(long projectId, long userId) {
        super("User " + userId + " is not a member of project " + projectId);
    }
}
