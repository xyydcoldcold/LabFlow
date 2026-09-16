package com.labflow.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import com.labflow.backend.auth.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectPermissionServiceTest {

    private static final long PROJECT_ID = 10L;
    private static final long USER_ID = 20L;
    private static final long OTHER_USER_ID = 30L;
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    private ProjectPermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new ProjectPermissionService(projectRepository, projectMemberRepository);
    }

    @Test
    void ownerRoleComesFromTheProjectWithoutAMembershipLookup() {
        arrangeProjectOwnedBy(USER_ID);

        ProjectRole role = permissionService.getRole(PROJECT_ID, USER_ID);

        assertThat(role).isEqualTo(ProjectRole.OWNER);
        verifyNoInteractions(projectMemberRepository);
    }

    @ParameterizedTest
    @MethodSource("collaboratorRoles")
    void collaboratorRoleComesFromProjectMembership(ProjectRole role) {
        arrangeRole(role);

        assertThat(permissionService.getRole(PROJECT_ID, USER_ID)).isEqualTo(role);
    }

    @Test
    void missingProjectIsReportedBeforeMembershipIsQueried() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.getRole(PROJECT_ID, USER_ID))
                .isInstanceOf(ProjectNotFoundException.class)
                .hasMessage("Project 10 was not found");
        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    void userWithoutOwnershipOrMembershipIsDenied() {
        arrangeProjectOwnedBy(OTHER_USER_ID);
        when(projectMemberRepository.findByProject_IdAndUser_Id(PROJECT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.getRole(PROJECT_ID, USER_ID))
                .isInstanceOf(ProjectAccessDeniedException.class)
                .hasMessage("Access to project 10 is denied");
    }

    @ParameterizedTest(name = "{0} may {1}")
    @MethodSource("allowedPermissions")
    void roleCanUseItsAllowedPermissions(ProjectRole role, RequiredPermission permission) {
        arrangeRole(role);

        assertThat(require(permission)).isEqualTo(role);
    }

    @ParameterizedTest(name = "{0} may not {1}")
    @MethodSource("deniedPermissions")
    void roleCannotUseHigherPermissions(ProjectRole role, RequiredPermission permission) {
        arrangeRole(role);

        assertThatThrownBy(() -> require(permission))
                .isInstanceOf(ProjectAccessDeniedException.class);
    }

    private void arrangeRole(ProjectRole role) {
        if (role == ProjectRole.OWNER) {
            arrangeProjectOwnedBy(USER_ID);
            return;
        }

        arrangeProjectOwnedBy(OTHER_USER_ID);
        ProjectMember membership = mock(ProjectMember.class);
        when(membership.getRole()).thenReturn(role);
        when(projectMemberRepository.findByProject_IdAndUser_Id(PROJECT_ID, USER_ID))
                .thenReturn(Optional.of(membership));
    }

    private void arrangeProjectOwnedBy(long ownerId) {
        AppUser owner = mock(AppUser.class);
        when(owner.getId()).thenReturn(ownerId);
        Project project = new Project(PROJECT_ID, "Quantum Chemistry", owner, NOW, NOW);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
    }

    private ProjectRole require(RequiredPermission permission) {
        return switch (permission) {
            case VIEW -> permissionService.requireView(PROJECT_ID, USER_ID);
            case CONTRIBUTE -> permissionService.requireContribute(PROJECT_ID, USER_ID);
            case MAINTAIN -> permissionService.requireMaintain(PROJECT_ID, USER_ID);
            case MANAGE_MEMBERS -> permissionService.requireManageMembers(PROJECT_ID, USER_ID);
        };
    }

    private static Stream<ProjectRole> collaboratorRoles() {
        return Stream.of(ProjectRole.MAINTAINER, ProjectRole.MEMBER, ProjectRole.VIEWER);
    }

    private static Stream<Arguments> allowedPermissions() {
        return Stream.of(
                Arguments.of(ProjectRole.OWNER, RequiredPermission.VIEW),
                Arguments.of(ProjectRole.OWNER, RequiredPermission.CONTRIBUTE),
                Arguments.of(ProjectRole.OWNER, RequiredPermission.MAINTAIN),
                Arguments.of(ProjectRole.OWNER, RequiredPermission.MANAGE_MEMBERS),
                Arguments.of(ProjectRole.MAINTAINER, RequiredPermission.VIEW),
                Arguments.of(ProjectRole.MAINTAINER, RequiredPermission.CONTRIBUTE),
                Arguments.of(ProjectRole.MAINTAINER, RequiredPermission.MAINTAIN),
                Arguments.of(ProjectRole.MEMBER, RequiredPermission.VIEW),
                Arguments.of(ProjectRole.MEMBER, RequiredPermission.CONTRIBUTE),
                Arguments.of(ProjectRole.VIEWER, RequiredPermission.VIEW)
        );
    }

    private static Stream<Arguments> deniedPermissions() {
        return Stream.of(
                Arguments.of(ProjectRole.MAINTAINER, RequiredPermission.MANAGE_MEMBERS),
                Arguments.of(ProjectRole.MEMBER, RequiredPermission.MAINTAIN),
                Arguments.of(ProjectRole.MEMBER, RequiredPermission.MANAGE_MEMBERS),
                Arguments.of(ProjectRole.VIEWER, RequiredPermission.CONTRIBUTE),
                Arguments.of(ProjectRole.VIEWER, RequiredPermission.MAINTAIN),
                Arguments.of(ProjectRole.VIEWER, RequiredPermission.MANAGE_MEMBERS)
        );
    }

    private enum RequiredPermission {
        VIEW,
        CONTRIBUTE,
        MAINTAIN,
        MANAGE_MEMBERS
    }
}
