package com.labflow.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.labflow.backend.auth.AppUser;
import com.labflow.backend.auth.AppUserRepository;
import com.labflow.backend.auth.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final long PROJECT_ID = 10L;
    private static final long OWNER_ID = 20L;
    private static final long MEMBER_ID = 30L;
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectPermissionService permissionService;

    @Mock
    private AppUserRepository userRepository;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectRepository,
                projectMemberRepository,
                permissionService,
                userRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createProjectNormalizesNameAndUsesTheCurrentUserAsOwner() {
        AppUser owner = mock(AppUser.class);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Project project = projectService.createProject(OWNER_ID, "  Quantum Chemistry  ");

        assertThat(project.getName()).isEqualTo("Quantum Chemistry");
        assertThat(project.getOwner()).isSameAs(owner);
        assertThat(project.getCreatedAt()).isEqualTo(NOW);
        assertThat(project.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void createProjectRejectsADeletedAuthenticatedUser() {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.createProject(OWNER_ID, "Quantum Chemistry"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void listVisibleProjectsUsesTheRepositoryVisibilityQuery() {
        Project project = projectWithOwner();
        when(projectRepository.findAllVisibleTo(OWNER_ID)).thenReturn(List.of(project));

        assertThat(projectService.listVisibleProjects(OWNER_ID)).containsExactly(project);
    }

    @Test
    void getVisibleProjectChecksPermissionBeforeReturningIt() {
        Project project = projectWithOwner();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        assertThat(projectService.getVisibleProject(PROJECT_ID, MEMBER_ID)).isSameAs(project);
        verify(permissionService).requireView(PROJECT_ID, MEMBER_ID);
    }

    @Test
    void addMemberNormalizesEmailAndCreatesTheMembership() {
        Project project = projectOwnedBy(OWNER_ID);
        AppUser member = user(MEMBER_ID);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(member));
        when(projectMemberRepository.existsByProject_IdAndUser_Id(PROJECT_ID, MEMBER_ID))
                .thenReturn(false);
        when(projectMemberRepository.saveAndFlush(any(ProjectMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProjectMember membership = projectService.addMember(
                PROJECT_ID,
                OWNER_ID,
                " Member@Example.COM ",
                ProjectRole.MEMBER
        );

        assertThat(membership.getId()).isEqualTo(new ProjectMemberId(PROJECT_ID, MEMBER_ID));
        assertThat(membership.getProject()).isSameAs(project);
        assertThat(membership.getUser()).isSameAs(member);
        assertThat(membership.getRole()).isEqualTo(ProjectRole.MEMBER);
        assertThat(membership.getCreatedAt()).isEqualTo(NOW);
        verify(permissionService).requireManageMembers(PROJECT_ID, OWNER_ID);
    }

    @Test
    void addMemberRejectsTheProjectOwnerAsACollaborator() {
        Project project = projectOwnedBy(OWNER_ID);
        AppUser owner = project.getOwner();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> projectService.addMember(
                PROJECT_ID,
                OWNER_ID,
                "owner@example.com",
                ProjectRole.MEMBER
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The project owner cannot be added as a collaborator");
    }

    @Test
    void addMemberRejectsAnExistingMembership() {
        Project project = projectOwnedBy(OWNER_ID);
        AppUser member = user(MEMBER_ID);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(member));
        when(projectMemberRepository.existsByProject_IdAndUser_Id(PROJECT_ID, MEMBER_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> projectService.addMember(
                PROJECT_ID,
                OWNER_ID,
                "member@example.com",
                ProjectRole.VIEWER
        )).isInstanceOf(ProjectMemberAlreadyExistsException.class);
    }

    @Test
    void addMemberRejectsOwnerAsAStoredMembershipRole() {
        assertThatThrownBy(() -> projectService.addMember(
                PROJECT_ID,
                OWNER_ID,
                "member@example.com",
                ProjectRole.OWNER
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership role must be MAINTAINER, MEMBER, or VIEWER");
    }

    @Test
    void updateMemberRoleChangesTheRoleAndTimestamp() {
        ProjectMember membership = mock(ProjectMember.class);
        when(projectMemberRepository.findByProject_IdAndUser_Id(PROJECT_ID, MEMBER_ID))
                .thenReturn(Optional.of(membership));
        when(projectMemberRepository.save(membership)).thenReturn(membership);

        assertThat(projectService.updateMemberRole(
                PROJECT_ID,
                OWNER_ID,
                MEMBER_ID,
                ProjectRole.MAINTAINER
        )).isSameAs(membership);

        verify(permissionService).requireManageMembers(PROJECT_ID, OWNER_ID);
        verify(membership).changeRole(ProjectRole.MAINTAINER, NOW);
    }

    @Test
    void removeMemberDeletesAnExistingMembership() {
        ProjectMember membership = mock(ProjectMember.class);
        when(projectMemberRepository.findByProject_IdAndUser_Id(PROJECT_ID, MEMBER_ID))
                .thenReturn(Optional.of(membership));

        projectService.removeMember(PROJECT_ID, OWNER_ID, MEMBER_ID);

        verify(permissionService).requireManageMembers(PROJECT_ID, OWNER_ID);
        verify(projectMemberRepository).delete(membership);
    }

    private Project projectOwnedBy(long ownerId) {
        return new Project(PROJECT_ID, "Quantum Chemistry", user(ownerId), NOW, NOW);
    }

    private Project projectWithOwner() {
        return new Project(PROJECT_ID, "Quantum Chemistry", mock(AppUser.class), NOW, NOW);
    }

    private AppUser user(long userId) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(userId);
        return user;
    }
}
