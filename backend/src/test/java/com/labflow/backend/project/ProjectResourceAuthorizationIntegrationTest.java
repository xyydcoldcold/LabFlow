package com.labflow.backend.project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.jayway.jsonpath.JsonPath;
import com.labflow.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc
class ProjectResourceAuthorizationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void projectsAreIsolatedAndViewerWritesAreRejectedAcrossResources() throws Exception {
        String ownerAToken = register("permission-owner-a@example.com", "Owner A");
        String ownerBToken = register("permission-owner-b@example.com", "Owner B");
        long projectAId = createProject(ownerAToken, "Private Project A");
        long projectBId = createProject(ownerBToken, "Private Project B");
        createConfig(ownerAToken, projectAId, 201);
        createConfig(ownerBToken, projectBId, 201);

        expectForbidden(get("/api/projects/{projectId}", projectAId), ownerBToken);
        expectForbidden(get("/api/projects/{projectId}/members", projectAId), ownerBToken);
        expectForbidden(get("/api/projects/{projectId}/inputs", projectAId), ownerBToken);
        expectForbidden(get("/api/projects/{projectId}/configs", projectAId), ownerBToken);
        expectForbidden(get("/api/projects/{projectId}/configs", projectBId), ownerAToken);

        mockMvc.perform(post("/api/projects/{projectId}/members", projectAId)
                        .header("Authorization", "Bearer " + ownerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "permission-owner-b@example.com",
                                  "role": "VIEWER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        mockMvc.perform(get("/api/projects/{projectId}/inputs", projectAId)
                        .header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects/{projectId}/configs", projectAId)
                        .header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Baseline"));

        createConfig(ownerBToken, projectAId, 403);
        expectForbidden(post("/api/projects/{projectId}/members", projectAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"permission-owner-a@example.com","role":"MEMBER"}
                        """), ownerBToken);
        mockMvc.perform(multipart("/api/projects/{projectId}/inputs", projectAId)
                        .file(new MockMultipartFile(
                                "file",
                                "hydrogen.xyz",
                                "chemical/x-xyz",
                                "1\nHydrogen\nH 0 0 0\n".getBytes(StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));
    }

    private String register(String email, String displayName) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "strong-password",
                                  "displayName": "%s"
                                }
                                """.formatted(email, displayName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private long createProject(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private void createConfig(String token, long projectId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/configs", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Baseline",
                                  "spec": {
                                    "schemaVersion": 1,
                                    "taskType": "pyscf.single_point",
                                    "method": "RHF",
                                    "basis": "sto-3g",
                                    "charge": 0,
                                    "spin": 0,
                                    "maxMemoryMb": 1024,
                                    "timeoutSeconds": 300
                                  }
                                }
                                """))
                .andExpect(status().is(expectedStatus));
    }

    private void expectForbidden(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String token
    ) throws Exception {
        mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));
    }
}
