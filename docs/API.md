# Implemented API

All user endpoints live under `/api` and use Bearer tokens unless noted. Worker endpoints under `/internal` use a separate service token.

## Authentication

Registering creates the account and returns a one-hour Bearer token:

```http
POST /api/auth/register
Content-Type: application/json

{
  "email": "scientist@example.com",
  "password": "strong-password",
  "displayName": "Ada Lovelace"
}
```

Log in with the same credentials:

```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "scientist@example.com",
  "password": "strong-password"
}
```

Validate a token and load the current account:

```http
GET /api/auth/me
Authorization: Bearer <access-token>
```

In production, the Caddy gateway rejects `POST /api/auth/register` with `403`; the operator creates accounts on the server with `python3 scripts/deploy.py create-user`.

Passwords are stored only as BCrypt hashes. Email addresses are trimmed and normalized to lowercase. Missing, expired, malformed, or incorrectly signed tokens receive the same non-leaking JSON `401` structure; authenticated authorization failures use the corresponding JSON `403` structure.

For non-local environments, provide a random `AUTH_JWT_SECRET` containing at least 32 UTF-8 bytes. `AUTH_JWT_ISSUER` and the ISO-8601 duration `AUTH_JWT_TTL` are also configurable.

## System information

```http
GET /api/system/info
```

Example response:

```json
{
  "service": "labflow-api",
  "version": "0.1.0",
  "status": "UP"
}
```

## Actuator health

```http
GET /actuator/health
```

The Actuator response includes PostgreSQL and RabbitMQ health. RabbitMQ also has its own Docker healthcheck.

## Versioned experiment configurations

Project contributors create an immutable configuration version with:

```http
POST /api/projects/{projectId}/configs
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "name": "Baseline",
  "spec": {
    "schemaVersion": 1,
    "taskType": "pyscf.single_point",
    "method": "RHF",
    "basis": "sto-3g",
    "charge": 0,
    "spin": 0,
    "maxMemoryMb": 1024,
    "timeoutSeconds": 300
  }
}
```

Posting the same name again creates the next version; it never updates an existing row. Project members can inspect every version with `GET /api/projects/{projectId}/configs` or fetch one through `GET /api/projects/{projectId}/configs/{configId}`. The version 1 contract is published at `backend/src/main/resources/schemas/experiment-config-v1.schema.json`. `GET /api/experiment-configs/limits` returns the server's memory and timeout limits, so the configuration form uses the same bounds as server validation.

## Idempotent job submission

Project owners, maintainers, and members can submit a job using an input and configuration from the same project:

```http
POST /api/jobs
Authorization: Bearer <access-token>
Idempotency-Key: <client-generated-unique-key>
Content-Type: application/json

{
  "projectId": 1,
  "molecularInputId": 2,
  "experimentConfigId": 3
}
```

The first request creates a `QUEUED` job, an idempotency record, a pending outbox event, and a job audit event in one transaction. The response is `201 Created` with `Location: /api/jobs/{id}`. Reusing the key in the same user/project scope with the same semantic request returns the original `201` response; reusing it with different IDs returns `409 IDEMPOTENCY_KEY_REUSED`. JSON property order and whitespace do not affect the request hash, and unsupported fields are rejected. A job submission stores an immutable snapshot of the input and configuration. The outbox publisher reliably delivers its execution signal to RabbitMQ, where a registered worker claims and executes it.

## Worker execution and observation

Workers authenticate to `/internal/**` with a dedicated service token, register their image digest and explicit capabilities, then atomically claim a fenced job attempt. They never execute broker-provided commands: the broker carries only IDs, and task dispatch is limited to the `demo.sleep_hash` and `pyscf.single_point` registry entries. Tasks run without a shell in a separate process group with a reduced environment, a wall-clock timeout, CPU limits, and a Linux address-space limit.

`pyscf.single_point` validates the immutable input checksum, parses XYZ coordinates, executes the configured SCF calculation, and records energy, convergence, timing, PySCF/Python/platform versions, image identity, spec hash, and artifact metadata. Ordered stdout/stderr/system chunks are persisted idempotently and exposed through both the job detail response and resumable SSE:

```http
GET /api/projects/{projectId}/jobs
GET /api/jobs/{jobId}
GET /api/jobs/{jobId}/events
Authorization: Bearer <access-token>
Last-Event-ID: <last-seen-log-id>
```

Set `WORKER_SERVICE_TOKEN` to the same random value of at least 32 bytes for the backend and workers outside local development.
