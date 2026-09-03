package com.labflow.backend.project;

import java.time.Instant;
import java.util.Objects;

import com.labflow.backend.auth.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_members")
public class ProjectMember {

    @EmbeddedId
    private ProjectMemberId id;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProjectMember() {
    }

    ProjectMember(Project project, AppUser user, ProjectRole role, Instant now) {
        Objects.requireNonNull(project);
        Objects.requireNonNull(user);
        if (project.getId() == null || user.getId() == null) {
            throw new IllegalArgumentException("Project and user must be persisted before adding membership");
        }

        this.id = new ProjectMemberId(project.getId(), user.getId());
        this.project = project;
        this.user = user;
        this.role = requireMembershipRole(role);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public ProjectMemberId getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public AppUser getUser() {
        return user;
    }

    public ProjectRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void changeRole(ProjectRole newRole, Instant now) {
        this.role = requireMembershipRole(newRole);
        this.updatedAt = Objects.requireNonNull(now);
    }

    private ProjectRole requireMembershipRole(ProjectRole candidate) {
        Objects.requireNonNull(candidate);
        if (!candidate.isMembershipRole()) {
            throw new IllegalArgumentException("OWNER is stored on the project, not as a membership role");
        }
        return candidate;
    }
}
