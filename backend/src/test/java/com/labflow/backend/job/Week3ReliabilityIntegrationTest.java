package com.labflow.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.labflow.backend.BackendApplication;
import com.labflow.backend.messaging.RabbitTopology;
import com.labflow.backend.outbox.OutboxBatchResult;
import com.labflow.backend.outbox.OutboxBatchService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Testcontainers
@SpringBootTest(
        classes = BackendApplication.class,
        properties = "labflow.outbox.publisher.enabled=false"
)
@AutoConfigureMockMvc
class Week3ReliabilityIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 50;
    private static final Path ARTIFACT_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "labflow-week3-reliability-" + UUID.randomUUID()
    );

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("labflow.artifacts.root", () -> ARTIFACT_ROOT.toString());
        registry.add("labflow.outbox.confirm-timeout", () -> "PT2S");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxBatchService batchService;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

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
    void fiftyConcurrentRetriesAndBrokerRecoveryPreserveTheJobSignal() throws Exception {
        assertTopologyDeclared();
        stopRabbitApplication();

        String token = register();
        long projectId = createProject(token);
        long inputId = uploadInput(token, projectId);
        long configId = createConfig(token, projectId);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> requests = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(20)) {
            for (int index = 0; index < CONCURRENT_REQUESTS; index++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return submit(token, projectId, inputId, configId);
                }));
            }
            start.countDown();

            String original = requests.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<String> request : requests) {
                assertThat(request.get(30, TimeUnit.SECONDS)).isEqualTo(original);
            }
        }

        long jobId = ((Number) JsonPath.read(requests.getFirst().get(), "$.id")).longValue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE project_id = ?", Integer.class, projectId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE job_id = ?", Integer.class, jobId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, jobId
        )).isEqualTo(1);

        OutboxBatchResult whileStopped = batchService.publishBatch();
        assertThat(whileStopped).isEqualTo(new OutboxBatchResult(1, 0, 1));
        var failedEvent = jdbcTemplate.queryForMap("""
                SELECT publish_attempts, available_at, published_at
                FROM outbox_events WHERE aggregate_id = ?
                """, jobId);
        assertThat(failedEvent)
                .containsEntry("publish_attempts", 1)
                .containsEntry("published_at", null);
        assertThat(failedEvent.get("available_at")).isNotNull();

        startRabbitApplication();
        jdbcTemplate.update("""
                UPDATE outbox_events SET available_at = CURRENT_TIMESTAMP WHERE aggregate_id = ?
                """, jobId);

        OutboxBatchResult recovered = batchService.publishBatch();
        assertThat(recovered).isEqualTo(new OutboxBatchResult(1, 1, 0));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?
                """, Boolean.class, jobId)).isTrue();

        Message delivered = rabbitTemplate.receive(RabbitTopology.JOBS_QUEUE, 5_000);
        assertThat(delivered).isNotNull();
        assertThat(delivered.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(delivered.getMessageProperties().getContentType()).isEqualTo("application/json");
        String payload = new String(delivered.getBody(), StandardCharsets.UTF_8);
        assertThat(((Number) JsonPath.read(payload, "$.jobId")).longValue()).isEqualTo(jobId);
        assertThat(((Number) JsonPath.read(payload, "$.eventId")).longValue()).isPositive();
        assertThat(((Number) JsonPath.read(payload, "$.schemaVersion")).intValue()).isEqualTo(1);
    }

    private void assertTopologyDeclared() {
        assertThat(rabbitAdmin.getQueueInfo(RabbitTopology.JOBS_QUEUE)).isNotNull();
        assertThat(rabbitAdmin.getQueueInfo(RabbitTopology.RETRY_15S_QUEUE)).isNotNull();
        assertThat(rabbitAdmin.getQueueInfo(RabbitTopology.RETRY_60S_QUEUE)).isNotNull();
        assertThat(rabbitAdmin.getQueueInfo(RabbitTopology.RETRY_300S_QUEUE)).isNotNull();
        assertThat(rabbitAdmin.getQueueInfo(RabbitTopology.JOBS_DLQ)).isNotNull();
    }

    private void stopRabbitApplication() throws Exception {
        var result = rabbitmq.execInContainer("rabbitmqctl", "stop_app");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private void startRabbitApplication() throws Exception {
        var result = rabbitmq.execInContainer("rabbitmqctl", "start_app");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        assertThat(rabbitmq.execInContainer("rabbitmq-diagnostics", "-q", "ping").getExitCode()).isZero();
        assertThat(rabbitAdmin.getQueueInfo(RabbitTopology.JOBS_QUEUE)).isNotNull();
    }

    private String submit(String token, long projectId, long inputId, long configId) throws Exception {
        return mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "week3-fifty-retries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d,"molecularInputId":%d,"experimentConfigId":%d}
                                """.formatted(projectId, inputId, configId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String register() throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"week3-reliability@example.com",
                                  "password":"strong-password",
                                  "displayName":"Week 3 Reliability"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private long createProject(String token) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Week 3 Reliability\"}"))
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

    private long createConfig(String token, long projectId) throws Exception {
        String response = mockMvc.perform(post("/api/projects/{projectId}/configs", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Baseline",
                                  "spec":{
                                    "schemaVersion":1,
                                    "taskType":"pyscf.single_point",
                                    "method":"RHF",
                                    "basis":"sto-3g",
                                    "charge":0,
                                    "spin":0,
                                    "maxMemoryMb":1024,
                                    "timeoutSeconds":300
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}
