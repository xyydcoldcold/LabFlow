package com.labflow.backend.molecularinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.labflow.backend.BackendApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc
class MolecularInputIntegrationTest {

    private static final Path ARTIFACT_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "labflow-input-integration-" + UUID.randomUUID()
    );

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void artifactProperties(DynamicPropertyRegistry registry) {
        registry.add("labflow.artifacts.root", () -> ARTIFACT_ROOT.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    static void deleteArtifacts() throws Exception {
        if (!Files.exists(ARTIFACT_ROOT)) {
            return;
        }
        List<Path> paths;
        try (var pathStream = Files.walk(ARTIFACT_ROOT)) {
            paths = pathStream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void uploadDeduplicatesWithinAProjectAndIsolatesProjects() throws Exception {
        String ownerAToken = register("owner-a@example.com", "Owner A");
        String ownerBToken = register("owner-b@example.com", "Owner B");
        long projectAId = createProject(ownerAToken, "Project A");
        long projectBId = createProject(ownerBToken, "Project B");
        byte[] content = validXyz();

        upload(projectAId, ownerAToken, content, 201);
        upload(projectAId, ownerAToken, content, 200);

        mockMvc.perform(get("/api/projects/{projectId}/inputs", projectAId)
                        .header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));

        upload(projectBId, ownerBToken, content, 201);

        Integer projectACount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM molecular_inputs WHERE project_id = ?",
                Integer.class,
                projectAId
        );
        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM molecular_inputs",
                Integer.class
        );
        assertThat(projectACount).isEqualTo(1);
        assertThat(totalCount).isEqualTo(2);
        try (var artifacts = Files.list(ARTIFACT_ROOT)) {
            assertThat(artifacts.count()).isEqualTo(2);
        }
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
                                {
                                  "name": "%s"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private void upload(long projectId, String token, byte[] content, int expectedStatus) throws Exception {
        mockMvc.perform(multipart("/api/projects/{projectId}/inputs", projectId)
                        .file(new MockMultipartFile(
                                "file",
                                "hydrogen.xyz",
                                "chemical/x-xyz",
                                content
                        ))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.sha256").value(
                        "ac320214c0999aeaf989356386836df037c824731536d6b9ac6dcb5e1fd66c45"
                ));
    }

    private byte[] validXyz() {
        return "2\nHydrogen molecule\nH 0 0 0\nH 0 0 0.74\n".getBytes(StandardCharsets.UTF_8);
    }
}
