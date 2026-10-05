# Week 5, Day 1: worker heartbeat and lease renewal

Implemented on 2026-10-05, following the implementation report's next reliability slice. Local unit validation passed; database/broker acceptance remains unverified because Docker Engine is unavailable on this machine.

## Behavior

- `POST /internal/workers/{workerId}/heartbeat` updates worker liveness using the backend `Clock`. Unknown workers return 404.
- `POST /internal/attempts/{attemptId}/heartbeat` requires the worker service token and `X-Attempt-Token`. It locks the attempt and job, checks ACTIVE/RUNNING ownership and an unexpired lease, then atomically updates the lease and worker liveness. Invalid ownership or expired leases return `409 STALE_ATTEMPT`.
- The response includes `attemptId`, `leaseExpiresAt`, and `cancelRequested`. Cancellation execution/acknowledgement remains a later slice.
- The worker confirms ownership before starting a task and renews at most every five seconds, or one third of the remaining lease for shorter leases. Renewal HTTP calls have a two-second timeout and no automatic retries.
- Idle workers report liveness every five seconds. Active attempt renewal also refreshes worker liveness.
- While waiting for the child process, the executor pumps RabbitMQ events and services lease renewal. Scheduling uses an injectable monotonic clock; lease expiry conversion uses an injectable wall clock.
- If renewal fails, execution stops conservatively: kill and reap the task process group, preserve the lease error during log cleanup, and nack the delivery without reporting a terminal job state. Transient renewal failures also take this path.
- Successful execution flushes logs and checks renewal scheduling again before submitting its terminal result.

## Validation

| Check | Result |
| --- | --- |
| Backend tests excluding `**/*IntegrationTest.class` via a temporary Gradle init script | 146 passed, zero failures/skips |
| Worker pytest suite | 28 passed |
| `git diff --check` | Passed |
| `WorkerExecutionIntegrationTest` | Blocked at Testcontainers initialization: Docker Engine unavailable |
| Live task longer than RabbitMQ's heartbeat interval | Not run |

The tests cover backend Clock-based timestamps, expiry boundary/past expiry, invalid token, inactive attempt, non-running job, worker liveness, cancellation response, five-second/short-lease scheduling, heartbeat HTTP timeout/authentication, idle scheduling, broker pumping, subprocess termination, and avoiding terminal reports after lease loss. The PostgreSQL integration test now includes worker and attempt heartbeat requests, renewed lease persistence, and rejection after completion; those assertions still need a Docker-backed run.

## Remaining acceptance

On a machine with Docker Engine available:

```bash
cd backend
./gradlew test
cd ../worker
.venv/bin/python -m pytest
```

Run a `demo.sleep_hash` task lasting more than 60 seconds with real RabbitMQ and PostgreSQL. During execution, verify that `job_attempts.lease_expires_at` keeps moving forward, `workers.last_heartbeat_at` advances, the AMQP connection stays open, and the job finishes once with one result row. Leave the worker idle and confirm its liveness timestamp continues advancing.

## Next slice

Week 5, Day 2 implements the RecoveryReaper: expired attempt → LOST, job → QUEUED, and a new Outbox event, with concurrent recovery protection. Until that exists, a lost worker or failed renewal can still leave a job RUNNING. This slice does not establish automatic failover, bounded retries, cancellation, or production long-job acceptance.
