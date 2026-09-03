package com.labflow.backend.project;

public enum ProjectRole {
    OWNER,
    MAINTAINER,
    MEMBER,
    VIEWER;

    public boolean isMembershipRole() {
        return this != OWNER;
    }
}
