package com.labflow.backend.project;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectPermissionService permissionService;

    public ProjectController(
            ProjectService projectService,
            ProjectPermissionService permissionService
    ) {
        this.projectService = projectService;
        this.permissionService = permissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        Project project = projectService.createProject(currentUserId(jwt), request.name());
        return ProjectResponse.from(project, ProjectRole.OWNER);
    }

    @GetMapping
    public List<ProjectResponse> listProjects(@AuthenticationPrincipal Jwt jwt) {
        long currentUserId = currentUserId(jwt);
        return projectService.listVisibleProjects(currentUserId).stream()
                .map(project -> toResponse(project, currentUserId))
                .toList();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId
    ) {
        long currentUserId = currentUserId(jwt);
        Project project = projectService.getVisibleProject(projectId, currentUserId);
        return toResponse(project, currentUserId);
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberResponse> listMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId
    ) {
        return projectService.listMembers(projectId, currentUserId(jwt)).stream()
                .map(ProjectMemberResponse::from)
                .toList();
    }

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemberResponse addMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId,
            @Valid @RequestBody AddProjectMemberRequest request
    ) {
        ProjectMember membership = projectService.addMember(
                projectId,
                currentUserId(jwt),
                request.email(),
                request.role()
        );
        return ProjectMemberResponse.from(membership);
    }

    @PatchMapping("/{projectId}/members/{memberUserId}")
    public ProjectMemberResponse updateMemberRole(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId,
            @PathVariable long memberUserId,
            @Valid @RequestBody UpdateProjectMemberRoleRequest request
    ) {
        ProjectMember membership = projectService.updateMemberRole(
                projectId,
                currentUserId(jwt),
                memberUserId,
                request.role()
        );
        return ProjectMemberResponse.from(membership);
    }

    @DeleteMapping("/{projectId}/members/{memberUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId,
            @PathVariable long memberUserId
    ) {
        projectService.removeMember(projectId, currentUserId(jwt), memberUserId);
    }

    private ProjectResponse toResponse(Project project, long currentUserId) {
        ProjectRole role = permissionService.getRole(project.getId(), currentUserId);
        return ProjectResponse.from(project, role);
    }

    private long currentUserId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
