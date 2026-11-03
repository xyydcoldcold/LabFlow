# Reliability model

How LabFlow turns RabbitMQ's at-least-once delivery into one accepted result per job. See the [README](../README.md) for the architecture overview.

## Job and attempt separation

A **Job** represents the computation requested by a user. An **Attempt** represents one worker's execution of that job. A worker failure creates another attempt for the same job rather than a duplicate job, preserving both the user's intent and the complete failure history.

## Transactional outbox

Creating a job and recording its pending message happens in the same PostgreSQL transaction. This prevents a committed `QUEUED` job from being silently lost if RabbitMQ is unavailable between the database write and message publication. A scheduled publisher claims available rows with `FOR UPDATE SKIP LOCKED`, sends persistent messages, waits for correlated publisher confirms, and only then records `published_at`. Failed sends remain unpublished and use capped exponential backoff. A crash after broker confirmation but before the database update can publish a duplicate, which is intentional under the system's at-least-once contract.

The version 1 broker message is deliberately small:

```json
{"jobId": 42, "eventId": 87, "schemaVersion": 1}
```

RabbitMQ declares a durable direct exchange, a durable quorum work queue, 15/60/300-second TTL retry queues, and a durable DLQ. Workers consume manually with prefetch one, claim jobs through the protected backend API, and acknowledge after the backend confirms a committed result, cancellation, or attempt failure with a durable retry decision.

## Lease and fencing token

Each claimed attempt receives a lease and a unique fencing token. Every new heartbeat, log, and terminal result write must present that token and an unexpired lease; writes from an expired or non-active attempt are rejected as `STALE_ATTEMPT`. Retries of an already committed terminal outcome remain idempotent for its original token. The worker renews active leases at most every five seconds (sooner for shorter leases), and updates worker liveness while idle. Renewal uses the backend Clock and rejects expired leases. The executor services RabbitMQ traffic during calculation and kills the task process group if renewal cannot be confirmed.

## Expired-attempt recovery

A RecoveryReaper scans every five seconds in bounded batches, locks expired attempts and jobs with `SKIP LOCKED`, and uses a job-version guard. It atomically marks the attempt `LOST`, advances the job version, records recovery history, and writes a new Outbox event when another attempt is allowed. Recovery at the attempt limit marks the job `FAILED` and records a durable DLQ signal. If cancellation is pending when the lease expires, recovery marks the attempt `LOST` and job `CANCELLED` without a retry message.

## Cancellation

Authenticated contributors can cancel their own jobs; project owners and maintainers can cancel any project job. QUEUED jobs cancel immediately. RUNNING jobs keep a pending request until the worker stops the subprocess and confirms cancellation, or the lease expires and recovery finalizes it. A committed cancellation request blocks later result/failure commits; a completed job stays final.

## Typed retries and failure replay

Transient task/network/log failures and expired worker leases retry through the 15/60/300-second TTL queues, with up to one second of publication jitter. The backend guards `next_attempt_at` so duplicate delivery cannot bypass the delay. New jobs have four total attempts; existing jobs keep their stored budget. Invalid input/configuration, non-converged SCF, resource limits, timeouts, and unknown errors fail without automatic retry. Exhausted transient failures produce a durable DLQ signal through the same transactional Outbox.

The job detail view shows attempt failures. Project owners and maintainers can use **Replay as a new job**, backed by `POST /api/jobs/{id}/replay` with an `Idempotency-Key`. Replay preserves the failed job and its history, creates a fresh job with the same immutable input/configuration references, and records its source ID. Repeated requests with the same key create one replay job.

## Unique final result

The `job_results` table has a primary key on `job_id` and a unique attempt constraint. Combined with transactional token validation, it is the final safeguard against duplicate or late result commits.
