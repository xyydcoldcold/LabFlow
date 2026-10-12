package com.labflow.backend.observability;

import java.util.List;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database-backed gauges survive restarts. IDs and user input are never metric labels. */
@Component
public class DatabaseMetrics {
    private final JdbcTemplate jdbc;

    public DatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String status : List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")) {
            Gauge.builder("labflow.jobs", this, ignored -> scalar("SELECT COUNT(*) FROM jobs WHERE status = ?", status))
                    .tag("status", status).register(registry);
        }
        for (String status : List.of("ACTIVE", "SUCCEEDED", "FAILED", "CANCELLED", "LOST")) {
            Gauge.builder("labflow.attempts", this, ignored -> scalar("SELECT COUNT(*) FROM job_attempts WHERE status = ?", status))
                    .tag("status", status).register(registry);
        }
        gauge(registry, "labflow.results", "SELECT COUNT(*) FROM job_results");
        gauge(registry, "labflow.outbox.pending", "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL");
        gauge(registry, "labflow.outbox.oldest.age", """
                SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - MIN(created_at)), 0), 0)
                FROM outbox_events WHERE published_at IS NULL
                """);
        gauge(registry, "labflow.jobs.queued.oldest.age", """
                SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - MIN(updated_at)), 0), 0)
                FROM jobs WHERE status = 'QUEUED'
                """);
        gauge(registry, "labflow.workers.live", """
                SELECT COUNT(*) FROM workers WHERE last_heartbeat_at > CURRENT_TIMESTAMP - INTERVAL '30 seconds'
                """);
    }

    private void gauge(MeterRegistry registry, String name, String sql) {
        var builder = Gauge.builder(name, this, ignored -> scalar(sql));
        if (name.endsWith(".age")) builder.baseUnit("seconds");
        builder.register(registry);
    }

    private double scalar(String sql, Object... parameters) {
        try {
            Number result = jdbc.queryForObject(sql, Number.class, parameters);
            return result == null ? Double.NaN : result.doubleValue();
        } catch (DataAccessException unavailable) {
            return Double.NaN; // An unavailable database must not masquerade as an empty queue.
        }
    }
}
