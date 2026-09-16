package com.labflow.backend.project;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.labflow.backend.auth.AppUser;
import com.labflow.backend.common.api.RestAccessDeniedHandler;
import com.labflow.backend.common.api.RestAuthenticationEntryPoint;
import com.labflow.backend.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProjectController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
        SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
class ProjectControllerTest {

    private static final long PROJECT_ID = 10L;
    private static final long OWNER_ID = 20L;
    private static final long MEMBER_ID = 30L;
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private ProjectPermissionService permissionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void projectApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/projects"));
    }

    @Test
    void createProjectReturns201AndOwnerRole() throws Exception {
        Project project = project(PROJECT_ID, OWNER_ID);
        when(projectService.createProject(OWNER_ID, "Quantum Chemistry")).thenReturn(project);

        mockMvc.perform(post("/api/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(OWNER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  Quantum Chemistry  "
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROJECT_ID))
                .andExpect(jsonPath("$.name").value("Quantum Chemistry"))
                .andExpect(jsonPath("$.owner.id").value(OWNER_ID))
                .andExpect(jsonPath("$.currentUserRole").value("OWNER"));

        verify(projectService).createProject(OWNER_ID, "Quantum Chemistry");
    }

    @Test
    void listProjectsReturnsOnlyServiceVisibleProjectsWithEffectiveRoles() throws Exception {
        Project project = project(PROJECT_ID, OWNER_ID);
        when(projectService.listVisibleProjects(MEMBER_ID)).thenReturn(List.of(project));
        when(permissionService.getRole(PROJECT_ID, MEMBER_ID)).thenReturn(ProjectRole.VIEWER);

        mockMvc.perform(get("/api/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(MEMBER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PROJECT_ID))
                .andExpect(jsonPath("$[0].owner.email").value("owner@example.com"))
                .andExpect(jsonPath("$[0].currentUserRole").value("VIEWER"));
    }

    @Test
    void addMemberReturns201AndSafeMemberResponse() throws Exception {
        ProjectMember membership = membership(ProjectRole.MEMBER);
        when(projectService.addMember(
                PROJECT_ID,
                OWNER_ID,
                "Member@Example.com",
                ProjectRole.MEMBER
        )).thenReturn(membership);

        mockMvc.perform(post("/api/projects/{projectId}/members", PROJECT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(OWNER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": " Member@Example.com ",
                                  "role": "MEMBER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.id").value(MEMBER_ID))
                .andExpect(jsonPath("$.user.email").value("member@example.com"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.joinedAt").exists());

        verify(projectService).addMember(
                PROJECT_ID,
                OWNER_ID,
                "Member@Example.com",
                ProjectRole.MEMBER
        );
    }

    @Test
    void updateMemberRoleReturnsTheUpdatedMembership() throws Exception {
        ProjectMember membership = membership(ProjectRole.MAINTAINER);
        when(projectService.updateMemberRole(
                PROJECT_ID,
                OWNER_ID,
                MEMBER_ID,
                ProjectRole.MAINTAINER
        )).thenReturn(membership);

        mockMvc.perform(patch("/api/projects/{projectId}/members/{memberUserId}", PROJECT_ID, MEMBER_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(OWNER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "MAINTAINER"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(MEMBER_ID))
                .andExpect(jsonPath("$.role").value("MAINTAINER"));
    }

    @Test
    void removeMemberReturns204WithoutABody() throws Exception {
        mockMvc.perform(delete("/api/projects/{projectId}/members/{memberUserId}", PROJECT_ID, MEMBER_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(OWNER_ID)))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(projectService).removeMember(PROJECT_ID, OWNER_ID, MEMBER_ID);
    }

    @Test
    void invalidProjectRequestReturnsTheStandard400Shape() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(OWNER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/api/projects"));
    }

    @Test
    void missingProjectReturnsTheProject404Shape() throws Exception {
        when(projectService.getVisibleProject(PROJECT_ID, OWNER_ID))
                .thenThrow(new ProjectNotFoundException(PROJECT_ID));

        mockMvc.perform(get("/api/projects/{projectId}", PROJECT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(OWNER_ID)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void deniedProjectAccessReturnsTheProject403Shape() throws Exception {
        when(projectService.getVisibleProject(PROJECT_ID, MEMBER_ID))
                .thenThrow(new ProjectAccessDeniedException(PROJECT_ID));

        mockMvc.perform(get("/api/projects/{projectId}", PROJECT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(MEMBER_ID)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void duplicateMembershipReturns409() throws Exception {
        when(projectService.addMember(
                PROJECT_ID,
                OWNER_ID,
                "member@example.com",
                ProjectRole.MEMBER
        )).thenThrow(new ProjectMemberAlreadyExistsException(PROJECT_ID));

        mockMvc.perform(post("/api/projects/{projectId}/members", PROJECT_ID)
                        .with(jwt().jwt(jwt -> jwt.subject(Long.toString(OWNER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "member@example.com",
                                  "role": "MEMBER"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_MEMBER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.status").value(409));
    }

    private Project project(long projectId, long ownerId) {
        return new Project(projectId, "Quantum Chemistry", user(ownerId, "owner@example.com"), NOW, NOW);
    }

    private ProjectMember membership(ProjectRole role) {
        return new ProjectMember(
                new Project(PROJECT_ID, "Quantum Chemistry", mock(AppUser.class), NOW, NOW),
                user(MEMBER_ID, "member@example.com"),
                role,
                NOW
        );
    }

    private AppUser user(long userId, String email) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(userId);
        when(user.getEmail()).thenReturn(email);
        when(user.getDisplayName()).thenReturn("Lab User");
        return user;
    }
}
