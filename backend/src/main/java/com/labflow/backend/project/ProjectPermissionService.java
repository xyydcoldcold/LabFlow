package com.labflow.backend.project;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProjectPermissionService {

    private static final Set<ProjectRole> CONTRIBUTOR_ROLES = EnumSet.of(
            ProjectRole.OWNER,
            ProjectRole.MAINTAINER,
            ProjectRole.MEMBER
    );
    private static final Set<ProjectRole> MAINTAINER_ROLES = EnumSet.of(
            ProjectRole.OWNER,
            ProjectRole.MAINTAINER
    );
    private static final Set<ProjectRole> OWNER_ROLES = EnumSet.of(ProjectRole.OWNER);

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectPermissionService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository
    ) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    public ProjectRole getRole(long projectId, long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        if (project.getOwner().getId().equals(userId)) {
            return ProjectRole.OWNER;
        }

        return projectMemberRepository.findByProject_IdAndUser_Id(projectId, userId)
                .map(ProjectMember::getRole)
                .orElseThrow(() -> new ProjectAccessDeniedException(projectId));
    }

    public ProjectRole requireView(long projectId, long userId) {
        return getRole(projectId, userId);
    }

    public ProjectRole requireContribute(long projectId, long userId) {
        return requireOneOf(projectId, userId, CONTRIBUTOR_ROLES);
    }

    public ProjectRole requireMaintain(long projectId, long userId) {
        return requireOneOf(projectId, userId, MAINTAINER_ROLES);
    }

    public ProjectRole requireManageMembers(long projectId, long userId) {
        return requireOneOf(projectId, userId, OWNER_ROLES);
    }

    private ProjectRole requireOneOf(long projectId, long userId, Set<ProjectRole> allowedRoles) {
        ProjectRole role = getRole(projectId, userId);
        if (!allowedRoles.contains(role)) {
            throw new ProjectAccessDeniedException(projectId);
        }
        return role;
    }
}
