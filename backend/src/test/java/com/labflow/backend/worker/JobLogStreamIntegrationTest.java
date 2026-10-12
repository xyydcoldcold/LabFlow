package com.labflow.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.labflow.backend.BackendApplication;
import com.labflow.backend.auth.AppUserRepository;
import com.labflow.backend.auth.JwtTokenService;
import com.labflow.backend.job.JobLogChunkResponse;
import com.labflow.backend.job.JobLogStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises real HTTP serialization, JWT security and commit-to-stream delivery. */
@Testcontainers
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"labflow.outbox.publisher.enabled=false", "labflow.recovery.reaper.enabled=false",
                "server.shutdown=immediate"})
@Timeout(30)
class JobLogStreamIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerExecutionService execution;
    @Autowired private JobLogStreamService streams;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<BufferedReader> readers = new ArrayList<>();

    @AfterEach
    void closeStreams() throws Exception {
        for (BufferedReader reader : readers) reader.close();
        client.close();
    }

    @Test
    void replaysPersistedLogsThenDeliversCommittedUtf8LogsAndResumesExclusively() throws Exception {
        var attempt = attempt();
        var first = append(attempt, 0, "SCF started\n");
        var second = append(attempt, 1, "energy Δ = −1.1 🧪\n");
        String token = token(attempt.userId());
        var reader = connect(attempt.jobId(), token, "0");
        assertEvent(reader, first);
        assertEvent(reader, second);

        // Repeating an upload must neither add a row nor broadcast a second event.
        assertThat(append(attempt, 1, second.content()).id()).isEqualTo(second.id());
        var live = append(attempt, 2, "live result\n");
        assertEvent(reader, live);
        assertThat(streams.loadAfter(attempt.jobId(), 0)).hasSize(3);
        reader.close();

        var resumed = connect(attempt.jobId(), token, Long.toString(second.id()));
        assertEvent(resumed, live);
        var next = append(attempt, 3, "after reconnect\n");
        assertEvent(resumed, next);
    }

    @Test
    void rolledBackLogIsNeitherPersistedNorBroadcast() throws Exception {
        var attempt = attempt();
        var first = append(attempt, 0, "ready\n");
        var reader = connect(attempt.jobId(), token(attempt.userId()), "0");
        assertEvent(reader, first);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            append(attempt, 1, "must not escape rollback\n");
            status.setRollbackOnly();
        });
        assertThat(streams.loadAfter(attempt.jobId(), first.id())).isEmpty();
        // A committed marker proves there was no ghost event ahead of it, without sleeps.
        assertEvent(reader, append(attempt, 1, "committed\n"));
    }

    @Test
    void streamsAreIsolatedByJobForReplayAndLiveDelivery() throws Exception {
        var a = attempt();
        var b = attempt();
        var first = append(a, 0, "job A\n");
        append(b, 0, "job B backlog\n");
        var reader = connect(a.jobId(), token(a.userId()), "0");
        assertEvent(reader, first);
        append(b, 1, "job B live\n");
        assertEvent(reader, append(a, 1, "job A live\n"));
    }

    @Test
    void enforcesAuthenticationProjectAccessAndCursorSyntaxButAllowsViewers() throws Exception {
        var owner = attempt();
        var outsider = attempt();
        assertError(owner.jobId(), null, "0", 401, "UNAUTHORIZED");
        assertError(owner.jobId(), "invalid-jwt", "0", 401, "UNAUTHORIZED");
        assertError(owner.jobId(), token(outsider.userId()), "0", 403, "PROJECT_ACCESS_DENIED");
        assertError(Long.MAX_VALUE, token(owner.userId()), "0", 404, "RESOURCE_NOT_FOUND");
        assertError(owner.jobId(), token(owner.userId()), "not-a-number", 400, "VALIDATION_FAILED");
        assertError(owner.jobId(), token(owner.userId()), "9223372036854775808", 400, "VALIDATION_FAILED");
        jdbc.update("INSERT INTO project_members (project_id, user_id, role) VALUES (?, ?, 'VIEWER')",
                owner.projectId(), outsider.userId());
        var log = append(owner, 0, "viewer can read\n");
        assertEvent(connect(owner.jobId(), token(outsider.userId()), "0"), log);
    }

    private WorkerTestFixtures.Attempt attempt() {
        Instant now = Instant.now();
        return WorkerTestFixtures.createAttempt(jdbc, now, 1, 4, now.plusSeconds(300));
    }

    private JobLogChunkResponse append(WorkerTestFixtures.Attempt attempt, long seq, String content) {
        return execution.appendLog(attempt.attemptId(), attempt.token(),
                new AppendLogRequest(seq, "STDOUT", content, Instant.now()));
    }

    private String token(long userId) {
        return tokens.issueToken(users.findById(userId).orElseThrow());
    }

    private HttpRequest request(long jobId, String token, String cursor) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/jobs/" + jobId + "/events"))
                .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").header("Last-Event-ID", cursor);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder.GET().build();
    }

    private void assertError(long jobId, String token, String cursor, int status, String code) throws Exception {
        var response = client.send(request(jobId, token, cursor), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode error = mapper.readTree(response.body());
        assertThat(error.path("status").intValue()).isEqualTo(status);
        assertThat(error.path("code").stringValue()).isEqualTo(code);
    }

    private BufferedReader connect(long jobId, String token, String cursor) throws Exception {
        var response = client.send(request(jobId, token, cursor), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new AssertionError("Expected SSE 200, got " + response.statusCode());
        }
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        readers.add(reader);
        return reader;
    }

    private void assertEvent(BufferedReader reader, JobLogChunkResponse expected) throws Exception {
        // Bound reads explicitly: a missing live event must fail instead of hanging CI.
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                String id = null;
                String name = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() && !data.isEmpty()) return new Event(id, name, mapper.readTree(data.toString()));
                    if (line.startsWith("id:")) id = line.substring(3).stripLeading();
                    if (line.startsWith("event:")) name = line.substring(6).stripLeading();
                    if (line.startsWith("data:")) data.append(line.substring(5).stripLeading());
                }
                throw new AssertionError("SSE ended before an event arrived");
            });
            Event event;
            try {
                event = future.get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                // Close the underlying HTTP stream before waiting on the blocked reader lock.
                client.shutdownNow();
                future.cancel(true);
                throw failure;
            }
            assertThat(event.id()).isEqualTo(Long.toString(expected.id()));
            assertThat(event.name()).isEqualTo("log");
            assertThat(event.data().path("id").longValue()).isEqualTo(expected.id());
            assertThat(event.data().path("attemptId").longValue()).isEqualTo(expected.attemptId());
            assertThat(event.data().path("seqNo").longValue()).isEqualTo(expected.seqNo());
            assertThat(event.data().path("content").stringValue()).isEqualTo(expected.content());
        } finally {
            executor.shutdownNow();
        }
    }

    private record Event(String id, String name, JsonNode data) { }
}
