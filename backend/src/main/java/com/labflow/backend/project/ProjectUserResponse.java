package com.labflow.backend.project;

import com.labflow.backend.auth.AppUser;

public record ProjectUserResponse(
        long id,
        String email,
        String displayName
) {
    static ProjectUserResponse from(AppUser user) {
        return new ProjectUserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
