#!/usr/bin/env python3
"""Single-host staging deployment. Never print resolved credentials or JWTs."""

import argparse
import datetime
import getpass
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env.production"
COMPOSE = ["docker", "compose", "--project-name", "labflow-prod", "--env-file",
           str(ENV_FILE), "--file", str(ROOT / "docker-compose.prod.yml")]
SECRET_NAMES = ("POSTGRES_PASSWORD", "RABBITMQ_PASSWORD", "AUTH_JWT_SECRET", "WORKER_SERVICE_TOKEN")


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, check=True, **kwargs)


def compose(*args, **kwargs):
    return run([*COMPOSE, *args], **kwargs)


def capture(command):
    return run(command, capture_output=True, text=True).stdout.strip()


def env_values():
    values = {}
    for line in ENV_FILE.read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    return values


def write_env(values, *, create=False):
    # Do not source this file as shell code. Generated values need no quoting.
    payload = "".join(f"{key}={value}\n" for key, value in values.items())
    if create:
        descriptor = os.open(ENV_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write(payload)
    else:
        temporary = ENV_FILE.with_suffix(".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(descriptor, "w") as stream:
                stream.write(payload)
            temporary.replace(ENV_FILE)
        finally:
            temporary.unlink(missing_ok=True)


def init(args):
    domain = args.domain.lower()
    if not re.fullmatch(r"(?=.{1,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", domain) or "." not in domain:
        raise ValueError("Use a DNS hostname without a scheme, port, or path")
    if not re.fullmatch(r"[^\s=@]+@[^\s=]+", args.email):
        raise ValueError("Use a valid ACME contact email")
    release = capture(["git", "rev-parse", "--short=12", "HEAD"])
    values = {"LABFLOW_DOMAIN": domain, "ACME_EMAIL": args.email, "LABFLOW_RELEASE": release}
    values.update({name: secrets.token_hex(32) for name in SECRET_NAMES})
    values["WORKER_IMAGE_DIGEST"] = "pending-build"
    write_env(values, create=True)
    print("Created .env.production with mode 0600 and random credentials. Values were not printed.")


def check():
    if not ENV_FILE.exists():
        raise ValueError("Run init on the server first")
    if ENV_FILE.stat().st_mode & 0o077:
        raise ValueError("Restrict .env.production permissions: chmod 600 .env.production")
    values = env_values()
    for name in SECRET_NAMES:
        value = values.get(name, "")
        if len(value.encode()) < 32 or any(marker in value.lower() for marker in (
                "change-me", "replace-with", "dev_password", "local-development", "local-worker")):
            raise ValueError(f"{name} must be a strong non-development credential")
    if len({values[name] for name in SECRET_NAMES}) != len(SECRET_NAMES):
        raise ValueError("Use distinct credentials for each service")
    # Capture output in memory: compose config normally contains all secrets.
    config = json.loads(capture([*COMPOSE, "config", "--format", "json"]))
    for name, service in config["services"].items():
        if name != "gateway" and service.get("ports"):
            raise ValueError(f"{name} must not publish ports")
    if {str(port["published"]) for port in config["services"]["gateway"]["ports"]} != {"80", "443"}:
        raise ValueError("Only gateway ports 80 and 443 may be published")
    backend = config["services"]["backend"]["environment"]
    expected = {"DB_PASSWORD": values["POSTGRES_PASSWORD"],
                "RABBITMQ_PASSWORD": values["RABBITMQ_PASSWORD"],
                "AUTH_JWT_SECRET": values["AUTH_JWT_SECRET"],
                "WORKER_SERVICE_TOKEN": values["WORKER_SERVICE_TOKEN"],
                "AUTH_JWT_ISSUER": "https://" + values["LABFLOW_DOMAIN"]}
    if any(backend.get(key) != value for key, value in expected.items()):
        raise ValueError("Shell environment overrides production settings; unset those variables first")
    print("Production configuration passed: credentials required; only ports 80/443 published.")


def active_jobs():
    query = "SELECT count(*) FROM jobs WHERE status IN ('QUEUED', 'RUNNING')"
    return int(capture([*COMPOSE, "exec", "-T", "postgres", "psql", "-U", "labflow",
                        "-d", "labflow", "-Atc", query]))


def drain(timeout):
    # The gateway is the only public ingress. Existing jobs can finish while it is stopped.
    compose("stop", "gateway")
    deadline = time.monotonic() + timeout
    while True:
        count = active_jobs()
        if count == 0:
            time.sleep(2)
            if active_jobs() == 0:
                return
        if time.monotonic() >= deadline:
            raise ValueError("Jobs did not drain. Gateway remains stopped; inspect jobs before restarting it.")
        print(f"Waiting for {count} queued/running jobs...")
        time.sleep(5)


def backup():
    directory = ROOT / "backups" / datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    directory.mkdir(parents=True, mode=0o700)
    for filename, command in (
            ("database.dump", ["exec", "-T", "postgres", "pg_dump", "-U", "labflow", "-d", "labflow", "-Fc"]),
            ("artifacts.tar.gz", ["exec", "-T", "backend", "tar", "-czf", "-", "-C", "/var/lib/labflow/artifacts", "."])):
        descriptor = os.open(directory / filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            compose(*command, stdout=stream)
    release = {"checkoutCommit": capture(["git", "rev-parse", "HEAD"]),
               "images": capture([*COMPOSE, "images"])}
    (directory / "release.json").write_text(json.dumps(release, indent=2) + "\n")
    print(f"Backup saved to {directory.relative_to(ROOT)}. Copy it off the VM.")


def up(args):
    if capture(["git", "status", "--porcelain"]):
        raise ValueError("Commit reviewed deployment changes first; deployment requires a clean checkout")
    values = env_values()
    values["LABFLOW_RELEASE"] = capture(["git", "rev-parse", "--short=12", "HEAD"])
    write_env(values)
    compose("build", "backend", "frontend", "worker")
    image = "labflow-worker:" + values["LABFLOW_RELEASE"]
    values["WORKER_IMAGE_DIGEST"] = capture(["docker", "image", "inspect", image, "--format", "{{.Id}}"])
    write_env(values)
    existing = capture([*COMPOSE, "ps", "-a", "--quiet", "backend"])
    if existing:
        drain(args.drain_timeout)
        backup()
    compose("up", "-d", "--wait", "--wait-timeout", "240")
    print(f"Containers started. Verify HTTPS, worker logs, and a real job at https://{values['LABFLOW_DOMAIN']}.")


def create_user():
    email = input("Account email: ").strip()
    name = input("Display name: ").strip()
    password = getpass.getpass("Password (not echoed): ")
    if password != getpass.getpass("Confirm password: "):
        raise ValueError("Passwords do not match")
    if len(password) < 8 or len(password.encode()) > 72:
        raise ValueError("Password must be at least 8 characters and at most 72 UTF-8 bytes")
    payload = json.dumps({"email": email, "password": password, "displayName": name}).encode()
    code = """import json, sys, urllib.request, urllib.error
request = urllib.request.Request('http://backend:8080/api/auth/register',
    data=sys.stdin.buffer.read(), headers={'Content-Type':'application/json'}, method='POST')
try:
    with urllib.request.urlopen(request, timeout=30) as response:
        body = json.load(response)
        print('Account created:', body['user']['email'])
except urllib.error.HTTPError as error:
    print('Account creation failed with HTTP', error.code, file=sys.stderr)
    sys.exit(1)
"""
    compose("exec", "-T", "worker", "python", "-c", code, input=payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    initialize = subparsers.add_parser("init", help="Generate server-local configuration without printing secrets")
    initialize.add_argument("--domain", required=True)
    initialize.add_argument("--email", required=True)
    subparsers.add_parser("check", help="Validate configuration without requiring a running Docker engine")
    deploy = subparsers.add_parser("up", help="Build; drain/backup an existing deployment; start containers")
    deploy.add_argument("--drain-timeout", type=int, default=360)
    subparsers.add_parser("status")
    subparsers.add_parser("create-user")
    save = subparsers.add_parser("backup", help="Stop ingress, drain jobs, and save DB/artifacts; gateway stays stopped")
    save.add_argument("--drain-timeout", type=int, default=360)
    subparsers.add_parser("resume", help="Resume public ingress after a backup or failed drain")
    args = parser.parse_args()
    if args.command == "init":
        init(args)
        return
    check()
    if args.command == "up":
        up(args)
    elif args.command == "status":
        compose("ps")
    elif args.command == "create-user":
        create_user()
    elif args.command == "backup":
        drain(args.drain_timeout)
        backup()
    elif args.command == "resume":
        compose("up", "-d", "gateway")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Do not include subprocess stdout/stderr: resolved configuration can contain secrets.
        print(f"Deployment stopped: {error}", file=sys.stderr)
        sys.exit(1)
