package com.labflow.backend.job;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.labflow.backend.project.ProjectPermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class JobLogStreamService {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1_000L;

    private final JdbcTemplate jdbcTemplate;
    private final ProjectPermissionService permissionService;
    private final ConcurrentHashMap<Long, StreamState> streams = new ConcurrentHashMap<>();

    public JobLogStreamService(JdbcTemplate jdbcTemplate, ProjectPermissionService permissionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionService = permissionService;
    }

    public SseEmitter subscribe(long jobId, long userId, long lastEventId) {
        Long projectId = jdbcTemplate.query("SELECT project_id FROM jobs WHERE id = ?", result ->
                result.next() ? result.getLong(1) : null, jobId);
        if (projectId == null) {
            throw new JobNotFoundException(jobId);
        }
        permissionService.requireView(projectId, userId);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        StreamState state = streams.computeIfAbsent(jobId, ignored -> new StreamState());
        synchronized (state) {
            for (JobLogChunkResponse chunk : loadAfter(jobId, lastEventId)) {
                if (!send(emitter, chunk)) {
                    if (state.emitters.isEmpty()) {
                        streams.remove(jobId, state);
                    }
                    return emitter;
                }
            }
            state.emitters.add(emitter);
        }
        Runnable cleanup = () -> remove(jobId, state, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        return emitter;
    }

    public List<JobLogChunkResponse> loadAfter(long jobId, long lastEventId) {
        return jdbcTemplate.query("""
                SELECT id, attempt_id, seq_no, stream, emitted_at, content
                FROM job_log_chunks
                WHERE job_id = ? AND id > ?
                ORDER BY id
                """, (row, rowNumber) -> new JobLogChunkResponse(
                row.getLong("id"),
                row.getLong("attempt_id"),
                row.getLong("seq_no"),
                row.getString("stream"),
                row.getTimestamp("emitted_at").toInstant(),
                row.getString("content")
        ), jobId, lastEventId);
    }

    public void broadcast(long jobId, JobLogChunkResponse chunk) {
        StreamState state = streams.get(jobId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.emitters.removeIf(emitter -> !send(emitter, chunk));
            if (state.emitters.isEmpty()) {
                streams.remove(jobId, state);
            }
        }
    }

    private boolean send(SseEmitter emitter, JobLogChunkResponse chunk) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(chunk.id()))
                    .name("log")
                    .data(chunk));
            return true;
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
            return false;
        }
    }

    private void remove(long jobId, StreamState state, SseEmitter emitter) {
        synchronized (state) {
            state.emitters.remove(emitter);
            if (state.emitters.isEmpty()) {
                streams.remove(jobId, state);
            }
        }
    }

    private static final class StreamState {
        private final List<SseEmitter> emitters = new ArrayList<>();
    }
}
