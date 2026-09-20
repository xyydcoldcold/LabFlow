package com.labflow.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
class JobSubmissionIntegrationTest {

    private static final Path ARTIFACT_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "labflow-job-submission-" + UUID.randomUUID()
    );

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void artifactProperties(DynamicPropertyRegistry registry) {
        registry.add("labflow.artifacts.root", () -> ARTIFACT_ROOT.toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterAll
    static void deleteArtifacts() throws Exception {
        if (!Files.exists(ARTIFACT_ROOT)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(ARTIFACT_ROOT)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void submissionAndEquivalentReplayProduceOneJobAndOnePendingOutboxEvent() throws Exception {
        String token = register("job-owner-1@example.com");
        long projectId = createProject(token, "Job Project 1");
        long inputId = uploadInput(token, projectId);
        long configId = createConfig(token, projectId, "Baseline");

        String firstResponse = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "job-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, inputId, configId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andReturn().getResponse().getContentAsString();
        long jobId = ((Number) JsonPath.read(firstResponse, "$.id")).longValue();

        String replayResponse = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "job-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "experimentConfigId": %d, "molecularInputId": %d, "projectId": %d }
                                """.formatted(configId, inputId, projectId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/jobs/" + jobId))
                .andReturn().getResponse().getContentAsString();

        assertThat(replayResponse).isEqualTo(firstResponse);
        assertThat(count("jobs", jobId)).isEqualTo(1);
        assertThat(count("idempotency_records", jobId)).isEqualTo(1);
        assertThat(count("outbox_events", jobId)).isEqualTo(1);
        assertThat(count("job_events", jobId)).isEqualTo(1);

        var job = jdbcTemplate.queryForMap("""
                SELECT status, version, spec_snapshot -> 'molecularInput' ->> 'sha256' AS input_sha,
                       spec_snapshot -> 'experimentConfig' ->> 'version' AS config_version
                FROM jobs WHERE id = ?
                """, jobId);
        assertThat(job).containsEntry("status", "QUEUED")
                .containsEntry("version", 0L)
                .containsEntry("input_sha", "ac320214c0999aeaf989356386836df037c824731536d6b9ac6dcb5e1fd66c45")
                .containsEntry("config_version", "1");
        var outbox = jdbcTemplate.queryForMap("""
                SELECT event_type, payload ->> 'jobId' AS job_id,
                       payload ->> 'eventId' AS event_id,
                       payload ->> 'schemaVersion' AS schema_version,
                       published_at
                FROM outbox_events WHERE aggregate_id = ?
                """, jobId);
        assertThat(outbox).containsEntry("event_type", "JOB_QUEUED")
                .containsEntry("job_id", Long.toString(jobId))
                .containsEntry("schema_version", "1")
                .containsEntry("published_at", null);
        assertThat(outbox.get("event_id")).isNotNull();
    }

    @Test
    void changedRequestWithSameKeyReturnsConflictWithoutCreatingAnotherJob() throws Exception {
        String token = register("job-owner-2@example.com");
        long projectId = createProject(token, "Job Project 2");
        long inputId = uploadInput(token, projectId);
        long firstConfigId = createConfig(token, projectId, "First");
        long secondConfigId = createConfig(token, projectId, "Second");

        submit(token, "job-key-2", projectId, inputId, firstConfigId, 201);
        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "job-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, inputId, secondConfigId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE project_id = ?", Integer.class, projectId
        )).isEqualTo(1);
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOnlyOneJob() throws Exception {
        String token = register("job-owner-concurrent@example.com");
        long projectId = createProject(token, "Concurrent Job Project");
        long inputId = uploadInput(token, projectId);
        long configId = createConfig(token, projectId, "Baseline");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<String>> requests = new java.util.ArrayList<>();
            for (int index = 0; index < 8; index++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return submit(token, "concurrent-key", projectId, inputId, configId, 201);
                }));
            }
            start.countDown();

            String first = requests.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<String> request : requests) {
                assertThat(request.get(30, TimeUnit.SECONDS)).isEqualTo(first);
            }
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE project_id = ?", Integer.class, projectId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_id IN (SELECT id FROM jobs WHERE project_id = ?)
                """, Integer.class, projectId
        )).isEqualTo(1);
    }

    @Test
    void theSameKeyCanBeUsedInAnotherProject() throws Exception {
        String token = register("job-owner-scope@example.com");
        long firstProject = createProject(token, "Scope Project 1");
        long secondProject = createProject(token, "Scope Project 2");
        long firstInput = uploadInput(token, firstProject);
        long secondInput = uploadInput(token, secondProject);
        long firstConfig = createConfig(token, firstProject, "Baseline");
        long secondConfig = createConfig(token, secondProject, "Baseline");

        String firstResponse = submit(token, "shared-key", firstProject, firstInput, firstConfig, 201);
        String secondResponse = submit(token, "shared-key", secondProject, secondInput, secondConfig, 201);

        long firstJobId = ((Number) JsonPath.read(firstResponse, "$.id")).longValue();
        long secondJobId = ((Number) JsonPath.read(secondResponse, "$.id")).longValue();
        assertThat(firstJobId).isNotEqualTo(secondJobId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM jobs WHERE project_id IN (?, ?)
                """, Integer.class, firstProject, secondProject
        )).isEqualTo(2);
    }

    @Test
    void rejectsCrossProjectInputsAndUnauthorizedSubmissionWithoutCreatingRecords() throws Exception {
        String tokenA = register("job-owner-a@example.com");
        String tokenB = register("job-owner-b@example.com");
        long projectA = createProject(tokenA, "Job Project A");
        long projectB = createProject(tokenB, "Job Project B");
        long inputA = uploadInput(tokenA, projectA);
        long configA = createConfig(tokenA, projectA, "Baseline");
        long configB = createConfig(tokenB, projectB, "Baseline");

        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "wrong-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectA, inputA, configB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + tokenB)
                        .header("Idempotency-Key", "unauthorized")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectA, inputA, configA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE project_id IN (?, ?)", Integer.class, projectA, projectB
        )).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM idempotency_records
                WHERE scope LIKE ? OR scope LIKE ?
                """, Integer.class, "project:" + projectA + ":%", "project:" + projectB + ":%"
        )).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_id IN (SELECT id FROM jobs WHERE project_id IN (?, ?))
                """, Integer.class, projectA, projectB
        )).isZero();
    }

    @Test
    void rejectsMissingOrInvalidIdempotencyKeyAndRequestBody() throws Exception {
        String token = register("job-owner-validation@example.com");

        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, 2, 3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, 2, 3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":0,\"molecularInputId\":2,\"experimentConfigId\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":1,\"molecularInputId\":2,\"experimentConfigId\":3,\"ignored\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String submit(String token, String key, long projectId, long inputId, long configId, int expectedStatus)
            throws Exception {
        return mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, inputId, configId)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private String body(long projectId, long inputId, long configId) {
        return """
                {"projectId":%d,"molecularInputId":%d,"experimentConfigId":%d}
                """.formatted(projectId, inputId, configId);
    }

    private int count(String table, long jobId) {
        String column = table.equals("outbox_events") ? "aggregate_id" : table.equals("jobs") ? "id" : "job_id";
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, jobId
        );
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"strong-password","displayName":"Job Owner"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private long createProject(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private long uploadInput(String token, long projectId) throws Exception {
        String response = mockMvc.perform(multipart("/api/projects/{projectId}/inputs", projectId)
                        .file(new MockMultipartFile(
                                "file", "hydrogen.xyz", "chemical/x-xyz",
                                "2\nHydrogen molecule\nH 0 0 0\nH 0 0 0.74\n".getBytes(StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private long createConfig(String token, long projectId, String name) throws Exception {
        String response = mockMvc.perform(post("/api/projects/{projectId}/configs", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
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
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}
