# Metrics and structured logs

## Local monitoring profile

The optional profile runs Prometheus and Grafana on the Compose network. It uses
a provisioned Prometheus data source and the **LabFlow operations** dashboard.
From the repository root:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml \
  --profile observability up --build -d --wait
```

Prometheus: http://127.0.0.1:19090. Grafana: http://127.0.0.1:13000,
dashboard `/d/labflow-operations`. The local Grafana fixture login is `admin` /
`labflow-local-admin-change-me`; `GRAFANA_ADMIN_PASSWORD` configures a fresh
Grafana database's initial password. Both monitoring ports bind only to localhost.
Without the profile, neither monitoring service starts.

The backend exposes `/actuator/prometheus` with a dedicated Bearer scrape
credential. Anonymous requests, user JWTs and worker service tokens cannot scrape.
The checked-in `infra/prometheus/dev-token` is a local development fixture. To use
a different local credential, set `METRICS_SCRAPE_TOKEN` for the backend and
`METRICS_TOKEN_FILE` to a file containing the same token for Prometheus. Do not put
real credentials in tracked files. The production profile exposes only health
and info by default; this local monitoring overlay is not a production deployment.

## Metrics

| Prometheus series | Meaning |
| --- | --- |
| `labflow_jobs{status}` | Current persisted job count for each of five states |
| `labflow_attempts{status}` | Current persisted attempt count, including ACTIVE and LOST |
| `labflow_results` | Number of accepted saved results |
| `labflow_job_events_total{event}` | Process-local committed lifecycle events: submission, claim, success, failed attempt, cancellation/request and lease recovery/exhaustion |
| `labflow_outbox_pending` | Unpublished events, including scheduled retry/dead-letter signals |
| `labflow_outbox_oldest_age_seconds` | Age of the oldest unpublished event, zero when none exists |
| `labflow_jobs_queued_oldest_age_seconds` | Age since the oldest currently queued job's last state update; includes delayed retries |
| `labflow_workers_live` | Workers with a heartbeat in the last 30 seconds |
| `http_server_requests_seconds_*` | Framework HTTP duration/count/histogram, including templated URI/status labels |

Lifecycle counters and logs are emitted **after transaction commit**. Rolled-back
work and duplicate terminal-result retries do not increment them. Counters reset
on backend restart; use `rate()` for event rates. Gauges query authoritative SQL
state at scrape time and survive process restarts. They are aggregate snapshots,
not a transactionally consistent multi-series export. They scan domain tables and
add read load; use a longer scrape interval or cached aggregates before scaling.
Database-unavailable gauges return NaN instead of falsely reporting an empty queue.

Only bounded `status` and `event` values are custom metric labels. Resource and
request IDs stay in logs. A scrape `up=1` verifies HTTP collection, not that every
database-backed gauge is valid. The dashboard's HTTP p95 uses histogram buckets
over a five-minute window; it is operational telemetry, not a controlled benchmark.
No RabbitMQ queue-depth exporter or Python Prometheus exporter is installed.

## Correlation and log semantics

Backend console logs use Spring Boot's Logstash JSON format. The request filter
accepts `X-Request-ID` matching `[A-Za-z0-9._-]{1,64}`, otherwise generates a UUID,
returns it in the response header/error body, and restores MDC after dispatch.
Request logs omit request bodies, query strings and credentials. An SSE request
log describes opening the asynchronous stream, not its eventual completion.

Open EntityManager in View is disabled: an idle SSE connection must not retain a
database connection for its lifetime. Project/member queries explicitly fetch the
owner/user fields needed by response DTOs. A real HTTP regression keeps four SSE
subscriptions open with a two-connection SQL pool and verifies detail reads and
live worker-log writes still complete.

Committed lifecycle records include `event`, `jobId`, and available `attemptId`
and `workerId`. HTTP-originated records also carry `requestId` through MDC.
Background recovery has no originating HTTP request. Do not add the same field
both as an MDC key and an SLF4J key/value: the JSON encoder rejects duplicate keys.

Python workers generate a request UUID for each internal API operation, preserving
it across HTTP retries. Their operational JSON events include `jobId`, `attemptId`,
`workerId`, and `requestId` where available. The latter connects the event to its
most recent internal request. Scientific stdout/stderr remains stored task logs;
third-party startup/connection messages may still be plain text. Tokens, results
and exception payloads are not included in operational events.

## Verification

```bash
python3 scripts/week7_acceptance.py
```

This builds a separate `labflow-w7-acceptance` stack and runs real scientific,
cancellation, SSE disconnect/reconnect, DLQ replay and monitoring checks. It
ignores deployment `.env` overrides, uses test credentials/localhost-only ports,
and stops its containers on exit while retaining its named volumes. It does not
stop or alter the development Compose project. `--keep-running` retains the
acceptance services for inspection; `--skip-build` reuses already-built images.

Reports and screenshots are under `frontend/test-results/week7/`; the runner
records UTC time, project name, image metadata and PASS/FAIL in
`test-results/week7/acceptance.json`. Both paths are ignored. CI uploads them.

The DLQ scenario stops only this stack's workers, sets **one isolated job** to a
one-attempt budget, and injects a typed transient failure through the real worker
API. It checks the actual RabbitMQ dead-letter message, preserves it in the queue,
then uses browser replay and real workers to produce exactly one saved result.
The browser SSE scenario drops a real stream after one server event and verifies
authenticated reconnection with its exact event ID and new log delivery. No API
responses or log events are fabricated. Longer retry TTL routing/redelivery is
covered separately by the backend tests; this suite does not wait through all
15/60/300-second tiers.

References: [Spring Boot structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html),
[Micrometer Prometheus registry](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html),
[Prometheus configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/),
[Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/).
