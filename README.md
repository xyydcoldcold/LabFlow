# LabFlow

> A reliable distributed job platform for reproducible scientific computing.

LabFlow is a portfolio project for laboratory teams that need to submit, run, observe, and compare scientific computing jobs. The planned system accepts validated molecular inputs and versioned PySCF configurations, dispatches work to Python workers, streams execution logs, and preserves enough provenance to explain and reproduce every result.

The central engineering problem is reliability rather than raw job volume. LabFlow is designed around RabbitMQ's **at-least-once delivery**: a job may be executed more than once after a failure, but leases, attempt tokens, and a database uniqueness constraint ensure that only one valid final result is accepted.



## Why LabFlow?

Scientific jobs are long-running and failure-prone. A worker can disappear after accepting a message, a broker can redeliver an event, and a client can retry the same submission. Treating these as normal system behavior leads to several explicit design goals:

- **Idempotent submission** — concurrent requests with the same `Idempotency-Key` create one job.
- **Recoverable execution** — heartbeats and leases allow an abandoned job to be claimed by another worker.
- **Fenced results** — a stale worker cannot overwrite the result of a newer attempt.
- **Reproducibility** — each job records immutable input, configuration, code, image, and runtime metadata.
- **Observable behavior** — job events, attempts, live logs, retry reasons, and recovery time remain inspectable.

LabFlow does **not** claim strict exactly-once execution. Its intended guarantee is:

> Work may run more than once, but only one authorized final result can be committed for a job.

## Target architecture

```mermaid
flowchart LR
    UI[React + TypeScript] -->|User API / SSE| API[Spring Boot API]
    API -->|State, metadata, outbox| DB[(PostgreSQL)]
    API -->|Publish job signal| MQ[(RabbitMQ)]
    MQ -->|At-least-once delivery| WORKER[Python Worker]
    WORKER -->|Claim, heartbeat, logs, result| API
    API --> ARTIFACTS[(Artifact volume)]
    WORKER --> ARTIFACTS
```

PostgreSQL is the source of truth for job state. RabbitMQ carries only execution signals. Workers do not write job state directly to the database; they use protected internal APIs so state transitions, authorization, lease checks, and result fencing remain centralized in the backend.

The intended execution flow is:

1. The API creates a job, an idempotency record, and an outbox event in one database transaction.
2. The outbox publisher sends the job ID to RabbitMQ.
3. A worker consumes the message and atomically claims a new job attempt.
4. The worker renews its lease, uploads ordered log chunks, and runs a whitelisted task.
5. The backend accepts the result only when the attempt token is still active.
6. If the lease expires, the old attempt becomes `LOST` and the job is queued for a new attempt.

## Planned reliability model

### Job and attempt separation

A **Job** represents the computation requested by a user. An **Attempt** represents one worker's execution of that job. A worker failure creates another attempt for the same job rather than a duplicate job, preserving both the user's intent and the complete failure history.

### Transactional outbox

Creating a job and recording its pending message happens in the same PostgreSQL transaction. This prevents a committed `QUEUED` job from being silently lost if RabbitMQ is unavailable between the database write and message publication. A scheduled publisher claims available rows with `FOR UPDATE SKIP LOCKED`, sends persistent messages, waits for correlated publisher confirms, and only then records `published_at`. Failed sends remain unpublished and use capped exponential backoff. A crash after broker confirmation but before the database update can publish a duplicate, which is intentional under the system's at-least-once contract.

The version 1 broker message is deliberately small:

```json
{"jobId": 42, "eventId": 87, "schemaVersion": 1}
```

RabbitMQ declares a durable direct exchange, a durable quorum work queue, 15/60/300-second TTL retry queues, and a durable DLQ. Consumer acknowledgement mode is manual with an initial prefetch of one. Worker consumption and claim semantics begin in Week 4.

### Lease and fencing token

Each claimed attempt receives a short-lived lease and a unique token. The worker renews the lease with heartbeats. Once the lease expires, late heartbeats, logs, or results from that attempt are rejected as `STALE_ATTEMPT`.

### Unique final result

The planned `job_result` schema has a unique constraint on `job_id`. Combined with transactional token validation, it acts as the final safeguard against duplicate or late result commits.

## Implemented API

### Authentication

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

Passwords are stored only as BCrypt hashes. Email addresses are trimmed and normalized to lowercase. Missing, expired, malformed, or incorrectly signed tokens receive the same non-leaking JSON `401` structure; authenticated authorization failures use the corresponding JSON `403` structure.

For non-local environments, provide a random `AUTH_JWT_SECRET` containing at least 32 UTF-8 bytes. `AUTH_JWT_ISSUER` and the ISO-8601 duration `AUTH_JWT_TTL` are also configurable.

### System information

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

### Actuator health

```http
GET /actuator/health
```

The Actuator response includes PostgreSQL and RabbitMQ health. RabbitMQ also has its own Docker healthcheck.

### Versioned experiment configurations

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

Posting the same name again creates the next version; it never updates an existing row. Project members can inspect every version with `GET /api/projects/{projectId}/configs` or fetch one through `GET /api/projects/{projectId}/configs/{configId}`. The version 1 contract is published at `backend/src/main/resources/schemas/experiment-config-v1.schema.json`.

### Idempotent job submission

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

The first request creates a `QUEUED` job, an idempotency record, a pending outbox event, and a job audit event in one transaction. The response is `201 Created` with `Location: /api/jobs/{id}`. Reusing the key in the same user/project scope with the same semantic request returns the original `201` response; reusing it with different IDs returns `409 IDEMPOTENCY_KEY_REUSED`. JSON property order and whitespace do not affect the request hash, and unsupported fields are rejected. A job submission stores an immutable snapshot of the input and configuration. The outbox publisher reliably delivers its execution signal to RabbitMQ; Worker execution is not yet implemented.

### Web workspace

The React workspace now provides the minimum Week 2 workflow: registration and login, visible-project selection, owner-only membership management, validated XYZ upload, and immutable experiment-config creation. Controls reflect the current project role, while the backend remains the authorization boundary.

For frontend development, start the backend on port `8080`, then run:

```bash
cd frontend
npm run dev
```

Vite proxies `/api` to the backend. The production Nginx image uses the same paths inside Docker Compose.

## Run the local stack

### Prerequisites

- Docker Engine or Docker Desktop with Docker Compose

The Compose file includes development defaults. Copy `.env.example` to `.env` first if you want to customize credentials or host ports.

```bash
git clone <https://github.com/xyydcoldcold/LabFlow>
cd LabFlow
docker compose up --build --wait
```

The local services are available at:

- Frontend: `http://localhost:3000`
- Backend API: `http://localhost:8080`
- Backend health: `http://localhost:8080/actuator/health`
- RabbitMQ Management: `http://localhost:15672`
- PostgreSQL: `localhost:5432`

Stop the stack without deleting persistent database or broker volumes:

```bash
docker compose down
```

Run the current checks with:

```bash
cd backend
./gradlew test
cd ../frontend
npm ci
npm run typecheck
cd ../worker
python3 -m pytest
```

## Technology stack

### Implemented

- Java 21
- Spring Boot 4.1
- Spring Web MVC
- Spring Security
- OAuth2 Resource Server JWT validation
- Spring Boot Actuator
- Gradle Kotlin DSL
- JUnit 5
- PostgreSQL 17 and Flyway
- RabbitMQ 4, Spring AMQP, publisher confirms, retry queues, and DLQ topology
- Python 3.12+ worker package and pytest
- React 19, TypeScript, and Vite
- Docker Compose
- Testcontainers
- GitHub Actions

### Selected for upcoming stages

- Spring AMQP and the transactional Outbox
- PySCF task execution
- Server-Sent Events (SSE)

## Repository layout

```text
LabFlow/
├── backend/                 # Spring Boot API and Flyway migrations
├── worker/                  # Python worker heartbeat scaffold
├── frontend/                # React/Vite scaffold served by Nginx
├── infra/                   # Reserved for broker and operational assets
├── tests/
│   ├── fault-injection/     # Worker and dependency failure scenarios
│   └── performance/         # Repeatable performance scenarios and results
├── docs/adr/                # Architecture decision records
├── .github/workflows/ci.yml # Backend, frontend, worker, and image checks
├── docker-compose.yml       # Six-service local development stack
└── README.md
```


## License

No license has been selected yet. Until a license is added, all rights are reserved.
