package com.labflow.backend.project;

import org.springframework.security.access.AccessDeniedException;

public class ProjectAccessDeniedException extends AccessDeniedException {

    public ProjectAccessDeniedException(long projectId) {
        super("Access to project " + projectId + " is denied");
    }
}
