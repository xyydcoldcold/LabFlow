# Test matrix and verification

The backend suite combines isolated unit tests with real PostgreSQL and RabbitMQ
Testcontainers tests. Tests use disposable containers rather than the development
Compose database. Docker Engine must be reachable before running integration tests.
No test is silently skipped when Docker is unavailable.

## Backend coverage

| Area | Tests | Assertions |
| --- | --- | --- |
| Services and permissions | `AuthServiceTest`, `ProjectServiceTest`, `ProjectPermissionServiceTest`, `MolecularInputServiceTest`, `ExperimentConfigServiceTest` | Authentication, role matrix, project-scoped reads/writes, immutable config versions, permission/validation rejection before persistence |
| State machine and retry policy | `JobStateMachineTest`, `FailurePolicyTest`, `RecoveryBatchServiceTest`, `RecoveryReaperTest` | Every allowed/forbidden state transition, terminal-state immutability, typed retry allowlist, bounded 15/60/300-second tiers, recovery scheduling |
| Validators and artifacts | `MolecularInputValidatorTest`, `ExperimentConfigValidatorTest`, `JobRequestHasherTest`, `FileSystemArtifactStorageTest` | UTF-8, paths, exact upload limit, finite coordinates, JSON types/integer overflow, inclusive execution limits, canonical request hashes, safe storage |
| Repositories and schema (PostgreSQL) | `DatabaseMigrationIntegrationTest`, `MolecularInputIntegrationTest`, `JobSubmissionIntegrationTest` | Flyway, foreign keys/checks/uniqueness, version history, input deduplication, idempotent submission and persisted timing |
| Security (PostgreSQL + HTTP) | `DatabaseMigrationIntegrationTest`, `ProjectResourceAuthorizationIntegrationTest`, `WorkerCancellationIntegrationTest`, `WorkerExecutionIntegrationTest`, `JobLogStreamIntegrationTest` | Real JWTs, project isolation, viewer restrictions, cancellation ownership and worker service-token checks |
| Outbox (PostgreSQL) | `OutboxBatchServiceIntegrationTest` | Publish confirmation, failure backoff, malformed-event isolation, concurrent `SKIP LOCKED`, transaction rollback after confirmation permits at-least-once redelivery |
| AMQP (PostgreSQL + RabbitMQ) | `Week3ReliabilityIntegrationTest`, `RetryIntegrationTest` | Fifty concurrent submissions, broker outage/recovery, persistent messages, real 15-second TTL redelivery, routing to longer retry tiers, exhaustion DLQ and idempotent replay |
| SSE (PostgreSQL + real HTTP server) | `JobLogStreamIntegrationTest` | Ordered backlog, event IDs and JSON serialization, UTF-8, exclusive `Last-Event-ID` replay followed by live delivery, duplicate-upload suppression, rollback never broadcasts, job isolation, owner/viewer access and JSON 401/403/404/400 errors |
| Worker execution and races (PostgreSQL) | `WorkerExecutionIntegrationTest`, `RecoveryBatchServiceIntegrationTest`, `WorkerCancellationIntegrationTest` | Fenced writes, expired leases, one saved result, recovery transaction rollback, concurrent reapers/renewal/claim/cancel/complete |

The SSE tests append logs through the transactional production service and observe
them through a real embedded HTTP server with signed JWTs. They do not mock the
database, stream emitter, serialization or security chain. Bounded event reads
fail on missing delivery; committed marker events detect unwanted delivery without
timing-dependent sleeps. Their test-only immediate server shutdown avoids waiting
for intentionally long-lived subscriptions.

The Outbox rollback test uses a mocked publisher to isolate the database transaction
boundary. Actual broker behavior is covered by the separate RabbitMQ suites.

## Run all checks

From the repository root:

```bash
cd backend
./gradlew test
cd ../frontend
npm ci
npm run typecheck
npm test
npm run build
cd ../worker
python3 -m pytest
```

With the Compose stack running, run the real browser acceptance flow:

```bash
cd frontend
npx playwright install chromium
npm run test:e2e
```

## Run and inspect

From the repository root:

```bash
docker info
backend/gradlew -p backend test
worker/.venv/bin/python -m pytest worker/tests
```

Gradle writes JUnit XML to `backend/build/test-results/test/` and an HTML report to
`backend/build/reports/tests/test/index.html`. Parameterized cases count separately.
CI runs the entire backend suite and uploads both reports even when a test fails.
Generated reports remain ignored by Git. A passing local run does not establish
remote CI or cloud deployment behavior.

For just the Week 7 Day 1 additions:

```bash
backend/gradlew -p backend test \
  --tests '*FailurePolicyTest' \
  --tests '*ExperimentConfigServiceTest' \
  --tests '*ExperimentConfigValidatorTest' \
  --tests '*MolecularInputValidatorTest' \
  --tests '*OutboxBatchServiceIntegrationTest' \
  --tests '*JobLogStreamIntegrationTest'
```

## Scope boundaries

This matrix describes behavioral assertions, not measured line/branch coverage or
a claim that every failure mode is covered. The 60/300-second retry queues have
routing coverage; only the 15-second tier is waited through in the backend suite.
Browser fixtures and the fictional demo are not scientific acceptance evidence.

## Isolated Compose E2E and observability

Run `python3 scripts/week7_acceptance.py` from the repository root after installing
frontend dependencies and Playwright Chromium. The isolated suite covers real
PySCF submit/result, same-key response-loss retry, viewer permissions, comparison,
running browser-to-worker cancellation, real browser SSE reconnect, exhausted
transient-failure DLQ routing, safe browser replay, authenticated Prometheus
scraping and provisioned Grafana. It is separate from backend-free UI fixtures.

`ObservabilityIntegrationTest` verifies committed-only counters, rollback and
duplicate-completion behavior, SQL-backed gauges, bounded labels and dedicated
metrics authorization. `RequestCorrelationFilterTest` checks safe ID propagation,
untrusted-header replacement and MDC cleanup. Worker tests verify stable request
IDs across transport retries and correlated JSON lifecycle output.

See [observability setup and acceptance semantics](OBSERVABILITY.md) for commands,
credentials, reports and the test-only one-attempt DLQ injection. Metrics/logging
are implemented; k6 loads, three-run median/p95 benchmarks, and dependency/worker
fault-injection benchmark evidence remain for Days 4–5. The earlier twenty-run
crash acceptance does not establish a new benchmark or cloud/default-lease results.

## Worker-crash acceptance

The isolated harness uses its own Compose project and volumes and only binds test HTTP ports to localhost. Build the standard local backend/worker images first, then build the latest acceptance overlays:

```bash
backend/gradlew -p backend bootJar
docker compose build backend worker-a
docker compose -p labflow-w5-local -f tests/fault-injection/compose.yml build
docker compose -p labflow-w5-local -f tests/fault-injection/compose.yml up -d --wait postgres rabbitmq backend frontend
python3 tests/fault-injection/week5.py --project labflow-w5-local --rounds 20
```

Each round SIGKILLs worker-a, starts worker-b, checks LOST + SUCCEEDED history, rejects old-token writes, and verifies exactly one result row. The JSON report at `test-results/week5-crash.json` stays local and records every run plus median/p95 timings and PASS/FAIL gates. This test stack uses a five-second lease, one-second recovery scan, and the actual 15/60/300-second retry TTLs; these timings do not characterize the default 30-second lease or a cloud VM.

For browser acceptance after the crash suite:

```bash
docker compose -p labflow-w5-local -f tests/fault-injection/compose.yml up -d --no-deps worker-b
cd frontend
LABFLOW_E2E_URL=http://127.0.0.1:13085 npm run test:e2e -- week5-replay.spec.ts
```

Stop the dedicated test stack from the repository root when finished:

```bash
docker compose -p labflow-w5-local -f tests/fault-injection/compose.yml down
```

Volumes remain available for evidence review. VM recovery, backup restoration, and scientific-throughput benchmarking require their own acceptance runs.
