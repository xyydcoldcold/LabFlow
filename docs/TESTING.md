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

Week 7 Day 2 covers the Compose E2E matrix, including live browser-to-worker
cancellation. Metrics and structured logs, k6 loads, three-run median/p95
benchmarks, and database/broker/worker fault-injection evidence belong to later
Week 7 days. The earlier twenty-run crash acceptance does not establish a new
benchmark or performance under default leases/cloud hardware.
