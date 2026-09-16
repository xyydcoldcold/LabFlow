package com.labflow.backend.project;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

import com.labflow.backend.auth.AppUser;
import com.labflow.backend.auth.AppUserRepository;
import com.labflow.backend.auth.InvalidCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectPermissionService permissionService;
    private final AppUserRepository userRepository;
    private final Clock clock;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectPermissionService permissionService,
            AppUserRepository userRepository,
            Clock clock
    ) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.permissionService = permissionService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public Project createProject(long currentUserId, String name) {
        AppUser owner = userRepository.findById(currentUserId)
                .orElseThrow(InvalidCredentialsException::new);
        Project project = new Project(normalizeProjectName(name), owner, clock.instant());
        return projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public List<Project> listVisibleProjects(long currentUserId) {
        return projectRepository.findAllVisibleTo(currentUserId);
    }

    @Transactional(readOnly = true)
    public Project getVisibleProject(long projectId, long currentUserId) {
        permissionService.requireView(projectId, currentUserId);
        return loadProject(projectId);
    }

    @Transactional(readOnly = true)
    public List<ProjectMember> listMembers(long projectId, long currentUserId) {
        permissionService.requireView(projectId, currentUserId);
        return projectMemberRepository.findAllByProject_IdOrderByCreatedAtAsc(projectId);
    }

    @Transactional
    public ProjectMember addMember(
            long projectId,
            long currentUserId,
            String memberEmail,
            ProjectRole role
    ) {
        permissionService.requireManageMembers(projectId, currentUserId);
        ProjectRole membershipRole = requireMembershipRole(role);
        Project project = loadProject(projectId);
        AppUser user = userRepository.findByEmail(normalizeEmail(memberEmail))
                .orElseThrow(ProjectMemberUserNotFoundException::new);

        if (project.getOwner().getId().equals(user.getId())) {
            throw new IllegalArgumentException("The project owner cannot be added as a collaborator");
        }
        if (projectMemberRepository.existsByProject_IdAndUser_Id(projectId, user.getId())) {
            throw new ProjectMemberAlreadyExistsException(projectId);
        }

        ProjectMember membership = new ProjectMember(project, user, membershipRole, clock.instant());
        try {
            return projectMemberRepository.saveAndFlush(membership);
        } catch (DataIntegrityViolationException exception) {
            throw new ProjectMemberAlreadyExistsException(projectId, exception);
        }
    }

    @Transactional
    public ProjectMember updateMemberRole(
            long projectId,
            long currentUserId,
            long memberUserId,
            ProjectRole role
    ) {
        permissionService.requireManageMembers(projectId, currentUserId);
        ProjectRole membershipRole = requireMembershipRole(role);
        ProjectMember membership = loadMembership(projectId, memberUserId);
        membership.changeRole(membershipRole, clock.instant());
        return projectMemberRepository.save(membership);
    }

    @Transactional
    public void removeMember(long projectId, long currentUserId, long memberUserId) {
        permissionService.requireManageMembers(projectId, currentUserId);
        projectMemberRepository.delete(loadMembership(projectId, memberUserId));
    }

    private Project loadProject(long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private ProjectMember loadMembership(long projectId, long userId) {
        return projectMemberRepository.findByProject_IdAndUser_Id(projectId, userId)
                .orElseThrow(() -> new ProjectMemberNotFoundException(projectId, userId));
    }

    private String normalizeProjectName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Project name is required");
        }
        String normalizedName = name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 200) {
            throw new IllegalArgumentException("Project name must contain between 1 and 200 characters");
        }
        return normalizedName;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Member email is required");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty() || normalizedEmail.length() > 320) {
            throw new IllegalArgumentException("Member email must contain between 1 and 320 characters");
        }
        return normalizedEmail;
    }

    private ProjectRole requireMembershipRole(ProjectRole role) {
        if (role == null || !role.isMembershipRole()) {
            throw new IllegalArgumentException("Membership role must be MAINTAINER, MEMBER, or VIEWER");
        }
        return role;
    }
}
