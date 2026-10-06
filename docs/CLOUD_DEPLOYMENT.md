# LabFlow cloud staging deployment

This deployment runs Caddy, Nginx/React, one Spring backend, one Python worker, PostgreSQL, and RabbitMQ on one Linux VM. It preserves the current shared-filesystem and single-backend SSE design. It is an account-controlled staging/demo deployment, not a high-availability release. The development `docker-compose.yml` remains separate.

Only Caddy publishes ports 80/443. The backend, worker APIs, database, and broker have no host ports. Caddy obtains and renews HTTPS certificates and redirects HTTP to HTTPS. Public registration is blocked at Caddy; an administrator creates accounts from the VM. Login and submission have per-client-IP limits at Nginx, and new configurations are limited to 1024 MB/300 seconds. The limits are for a small demo, not a measured throughput guarantee.

The Docker networks are private on this single host; traffic between containers is not encrypted. This configuration does not connect to managed databases/brokers. Moving services off-host requires verified TLS and a different artifact delivery mechanism.

## What the repository now supplies

- `docker-compose.prod.yml`: independent staging topology, persistent volumes, resource limits, bounded container logs, and commit-tagged application builds.
- `infra/Caddyfile` and `frontend/nginx.prod.conf`: HTTPS ingress, blocked registration/internal/Actuator paths, forwarding headers, rate limits, and unbuffered SSE.
- Backend `prod` profile: explicit credentials, rejection of development secrets, private health details, liveness/readiness probes, graceful HTTP shutdown, and configuration resource ceilings.
- `scripts/deploy.py`: private secret generation, preflight validation, builds, maintenance/drain checks, database/artifact backups, and account provisioning. It does not print secrets, passwords, or issued tokens.
- Deployment regression tests and CI proxy validation. Building on the VM avoids requiring a registry account for the first deployment.

## What you need to provide

1. A cloud account, billing approval, and a Linux VM that you administer. Use a Linux architecture supported by the worker's PySCF wheel and run the scientific acceptance test on that architecture. A starting staging allocation is 4 vCPU/8 GB RAM with persistent disk; actual sizing still needs measurement. The Compose runtime memory ceilings total about 5.2 GB, excluding OS overhead and build processes. Smaller machines require adjusted limits and verification.
2. A domain/subdomain and DNS access. Point its A record to the VM's public IPv4 address. Publish an AAAA record only if IPv6 connectivity is actually configured.
3. SSH access. In the cloud firewall, allow TCP 22 only from your administrator IP; allow TCP 80/443 for certificate issuance and web access. Keep 5432, 5672, 15672, 8080, and 3000 closed. Apply equivalent host firewall rules. Docker-published ports need particular care because they can bypass some host firewall rules.
4. A plan for encrypted, off-VM backups and disk monitoring. A backup on the same VM does not protect against loss of the machine or disk.

Codex cannot provision paid resources, change DNS, install services on your remote VM, or verify real HTTPS without an authorized cloud/server connection and the actual domain. None of those remote actions has been performed by adding these files. Do not paste account secrets, SSH private keys, or the production environment file into chat.

## First deployment

1. Review and commit these repository changes, then push them to your repository. `up` requires a clean checkout so its image tag corresponds to committed source. On the VM, install Git, Python 3, and Docker Engine with the Compose plugin. Use Docker's instructions for your chosen Linux distribution; do not install a second Compose implementation. Only trusted administrators should have Docker access.

   Docker installation: https://docs.docker.com/engine/install/

2. Clone the updated repository onto the VM and enter it. Use the same checkout directory for subsequent deployments, since production environment files and backup metadata stay there. Confirm the domain resolves to this VM and Docker is working:

   ```bash
   docker info
   docker compose version
   ```

3. Replace the two example arguments with your real domain and contact email. Generate credentials on the VM; keep the file there, mode 0600. Generation refuses to overwrite an existing file.

   ```bash
   python3 scripts/deploy.py init --domain labflow.your-domain.com --email you@your-domain.com
   python3 scripts/deploy.py check
   python3 scripts/deploy.py up
   python3 scripts/deploy.py status
   ```

   Use only `docker-compose.prod.yml` for this deployment. Do not merge it with the development file, run plain `docker compose up`, print `compose config` output, or source the environment file as shell code. The helper captures resolved configuration without displaying it.

   The first build downloads dependencies and images. The helper records the built worker's local content-addressed image ID in the manifest; this is an image ID, not a registry RepoDigest. Third-party image tags and Python dependency ranges are not fully pinned yet. For a release that must be rebuilt identically, pin base images and lock Python dependencies, or distribute immutable registry images.

4. Provision your own account without temporarily enabling public registration:

   ```bash
   python3 scripts/deploy.py create-user
   ```

   The command prompts for email/name/password, sends the password through stdin over the private container network, and prints only the created email. It requires the worker container to be running. The UI's registration tab remains visible, but public registration returns a contact-administrator message; use the login tab.

5. Open `https://YOUR_DOMAIN`, verify the browser trusts the certificate, and log in. Create a project; upload `examples/h2.xyz`; create RHF/`sto-3g`, charge 0, spin 0, 1024 MB, 300-second configuration; submit it. Verify a successful attempt, live logs, the manifest, and energy approximately −1.1167593074 Hartree. Check that `/internal/workers/register` and `/actuator/health` return 404 publicly and `/api/auth/register` returns 403. Check externally that only intended ports are reachable.

   `up --wait` checks container health for services that define healthchecks; it does not prove that the worker registered or that PySCF runs. The real job test is required. From the VM, inspect container status/logs using:

   ```bash
   python3 scripts/deploy.py status
   docker compose --project-name labflow-prod --env-file .env.production -f docker-compose.prod.yml logs --tail=100 worker backend gateway
   ```

   Do not share unreviewed runtime logs: task errors can contain user input and infrastructure information.

## Updates and backups

Take a maintenance window. Update to the reviewed commit and run `python3 scripts/deploy.py up`. It builds images before stopping the gateway, then stops public ingress, drains all `QUEUED`/`RUNNING` jobs, and saves a PostgreSQL custom-format dump plus an artifact archive before replacing services. Persistent database, broker, artifact, and certificate volumes remain in place. Backend Flyway migrations run on startup; never edit an already-applied migration.

If the drain deadline is reached or backup/startup fails, the helper stops and leaves the gateway closed rather than interrupting jobs. Investigate first. To restore access to the existing running services after a failed drain:

```bash
python3 scripts/deploy.py resume
```

To take an independent backup:

```bash
python3 scripts/deploy.py backup
python3 scripts/deploy.py resume
```

Backups are written to `backups/<UTC timestamp>/`, excluded from Git, with database/artifact files mode 0600. They contain private user data and password hashes. Copy the directory to encrypted storage outside this VM, and separately secure the `.env.production` recovery copy. Secret changes do not rotate existing PostgreSQL/RabbitMQ accounts automatically; perform coordinated database/broker credential rotation instead of simply regenerating the file.

A rollback after a schema migration is not just checking out an older commit. Restore into an isolated recovery stack first, using the original application version and credential configuration. Restore `database.dump` with PostgreSQL 17 `pg_restore`, and restore `artifacts.tar.gz` into its matching artifact volume. Set ownership to UID/GID 10001 for backend access. Validate input checksums and a real job before switching traffic. Test this restore procedure before relying on the backup; the helper creates backups but does not automate or prove restoration. Do not run `down -v` on the live stack.

## Limits to resolve before a reliable public release

- Worker heartbeats, lease renewal, and expired-attempt recovery are implemented. The reaper requeues eligible jobs and fails jobs that reach the attempt limit. Lease fencing and API/worker cancellation are also implemented; typed-error TTL retries, bounded attempts, DLQ signals, and maintainer replay are implemented. Local PostgreSQL tests cover recovery races and transaction rollback; 20 local SIGKILL takeovers are covered by the isolated acceptance harness; restart recovery on the target VM still requires environment-level acceptance. Do not manually rewrite production job state without a reviewed recovery procedure.
- The executor pumps RabbitMQ events while waiting for the task subprocess and renews the attempt lease. A failed renewal stops the subprocess without submitting a terminal result; the reaper handles its expired lease when recovery is enabled, finalizing pending cancellation instead of retrying. Verify a task longer than the broker heartbeat interval with real RabbitMQ before accepting long jobs; unit tests do not prove this behavior on the VM.
- Resource ceilings apply to newly created configurations. Existing configurations imported from another deployment may exceed them; audit those before migration. Rate limits do not replace per-account queue/storage quotas or abuse protection.
- Keep one backend and one named worker in this topology. SSE subscriber state is local to the backend, and workers need the same mounted artifact path. Scaling services across hosts requires shared notification and artifact storage changes.
- There is no high availability, registry release pipeline, automated restore, or cloud monitoring here. Monitor disk space, unpublished Outbox events, queued/running job ages, worker registration, and certificate renewal before expanding access.
- Backend/container/browser acceptance, public DNS, real certificate issuance, restart persistence, backup/restore, and worker-crash recovery must be checked in their actual environment. The repository tests do not establish remote deployment success.

References: [Docker production Compose](https://docs.docker.com/compose/how-tos/production/), [Docker firewall behavior](https://docs.docker.com/engine/network/packet-filtering-firewalls/), [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https), [Spring Boot probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), and [Pika long-running consumers](https://pika.readthedocs.io/en/stable/modules/adapters/index.html).
