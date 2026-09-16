package com.labflow.backend.project;

public class ProjectMemberUserNotFoundException extends RuntimeException {

    public ProjectMemberUserNotFoundException() {
        super("No registered user matches the supplied email");
    }
}
