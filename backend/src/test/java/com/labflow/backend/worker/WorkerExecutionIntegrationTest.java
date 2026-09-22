package com.labflow.backend.worker;

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
import com.labflow.backend.job.JobLogStreamService;
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
@SpringBootTest(
        classes = BackendApplication.class,
        properties = "labflow.outbox.publisher.enabled=false"
)
@AutoConfigureMockMvc
class WorkerExecutionIntegrationTest {

    private static final String SERVICE_TOKEN = "labflow-local-worker-service-token-change-me";
    private static final Path ARTIFACT_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "labflow-worker-test-" + UUID.randomUUID()
    );

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("labflow.artifacts.root", () -> ARTIFACT_ROOT.toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JobLogStreamService logStreamService;

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
    void workerCanClaimLogAndCompleteAJobExactlyOnce() throws Exception {
        String userToken = register("worker-flow@example.com");
        long projectId = createProject(userToken);
        long inputId = uploadInput(userToken, projectId);
        long configId = createConfig(userToken, projectId);
        long jobId = submit(userToken, projectId, inputId, configId);

        String workerResponse = workerPost("/internal/workers/register", """
                {
                  "instanceName":"worker-integration",
                  "imageDigest":"sha256:test-image",
                  "capabilities":["demo.sleep_hash","pyscf.single_point"]
                }
                """, null, 200);
        long workerId = ((Number) JsonPath.read(workerResponse, "$.workerId")).longValue();

        String claimResponse = workerPost("/internal/jobs/" + jobId + "/claim",
                "{\"workerId\":" + workerId + "}", null, 200);
        long attemptId = ((Number) JsonPath.read(claimResponse, "$.attemptId")).longValue();
        String attemptToken = JsonPath.read(claimResponse, "$.attemptToken");
        String inputPath = JsonPath.read(claimResponse, "$.inputArtifactPath");
        assertThat(Path.of(inputPath)).isRegularFile().startsWith(ARTIFACT_ROOT);

        String log = """
                {"seqNo":0,"stream":"STDOUT","emittedAt":"2026-09-21T12:00:00Z","content":"SCF started\\n"}
                """;
        String firstLog = workerPost("/internal/attempts/" + attemptId + "/logs", log, attemptToken, 201);
        String replayedLog = workerPost("/internal/attempts/" + attemptId + "/logs", log, attemptToken, 201);
        long replayedLogId = ((Number) JsonPath.read(replayedLog, "$.id")).longValue();
        long firstLogId = ((Number) JsonPath.read(firstLog, "$.id")).longValue();
        assertThat(replayedLogId).isEqualTo(firstLogId);
        assertThat(logStreamService.loadAfter(jobId, 0)).hasSize(1);

        String result = """
                {
                  "summary":{"energyHartree":-1.1167593074,"converged":true,"durationSeconds":0.42},
                  "manifest":{"environment":{"workerImageDigest":"sha256:test-image"},"artifacts":[]}
                }
                """;
        workerPost("/internal/attempts/" + attemptId + "/succeed", result, attemptToken, 200);
        workerPost("/internal/attempts/" + attemptId + "/succeed", result, attemptToken, 200);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_results WHERE job_id = ?", Integer.class, jobId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?", String.class, jobId
        )).isEqualTo("SUCCEEDED");

        mockMvc.perform(get("/api/jobs/{jobId}", jobId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.logs[0].content").value("SCF started\n"))
                .andExpect(jsonPath("$.result.summary.energyHartree").value(-1.1167593074))
                .andExpect(jsonPath("$.result.manifest.environment.workerImageDigest")
                        .value("sha256:test-image"));
        mockMvc.perform(get("/api/projects/{projectId}/jobs", projectId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(jobId))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));

        workerPost("/internal/attempts/" + attemptId + "/fail",
                "{\"error\":{\"code\":\"LATE_FAILURE\"}}", attemptToken, 409);
    }

    @Test
    void internalEndpointsRequireTheDedicatedServiceToken() throws Exception {
        String payload = """
                {"instanceName":"intruder","imageDigest":"test","capabilities":["pyscf.single_point"]}
                """;
        mockMvc.perform(post("/internal/workers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/workers/register")
                        .header("Authorization", "Bearer not-the-service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    private String workerPost(String path, String body, String attemptToken, int expectedStatus) throws Exception {
        var request = post(path)
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (attemptToken != null) {
            request.header("X-Attempt-Token", attemptToken);
        }
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"strong-password","displayName":"Worker Owner"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private long createProject(String token) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Worker Integration\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private long uploadInput(String token, long projectId) throws Exception {
        String response = mockMvc.perform(multipart("/api/projects/{projectId}/inputs", projectId)
                        .file(new MockMultipartFile(
                                "file", "h2.xyz", "chemical/x-xyz",
                                "2\nH2\nH 0 0 0\nH 0 0 0.74\n".getBytes(StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private long createConfig(String token, long projectId) throws Exception {
        String response = mockMvc.perform(post("/api/projects/{projectId}/configs", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"H2 STO-3G","spec":{"schemaVersion":1,
                                "taskType":"pyscf.single_point","method":"RHF","basis":"sto-3g",
                                "charge":0,"spin":0,"maxMemoryMb":1024,"timeoutSeconds":300}}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private long submit(String token, long projectId, long inputId, long configId) throws Exception {
        String response = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "worker-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d,"molecularInputId":%d,"experimentConfigId":%d}
                                """.formatted(projectId, inputId, configId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}
