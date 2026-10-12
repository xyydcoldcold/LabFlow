#!/usr/bin/env python3
"""Run real Week 7 acceptance in a separately named Compose project."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default="labflow-w7-acceptance")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--keep-running", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"labflow-w7-[a-z0-9-]+", args.project):
        parser.error("project must start with labflow-w7-; development projects are forbidden")
    command = ["docker", "compose", "--env-file", "/dev/null", "--project-directory", str(ROOT), "-p", args.project,
               "-f", str(ROOT / "tests/e2e/compose.yml"), "-f", str(ROOT / "docker-compose.observability.yml"),
               "--profile", "observability"]
    # Acceptance credentials and ports are fixed fixtures. Do not inherit real deployment overrides.
    env = dict(os.environ)
    for key in ["METRICS_SCRAPE_TOKEN", "METRICS_TOKEN_FILE", "GRAFANA_ADMIN_PASSWORD", "PROMETHEUS_PORT", "GRAFANA_PORT"]:
        env.pop(key, None)
    env["LABFLOW_E2E_PROJECT"] = args.project
    evidence = ROOT / "test-results/week7"
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"project": args.project, "dateUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "passed": False}
    try:
        subprocess.run(["docker", "info"], env=env, stdout=subprocess.DEVNULL, check=True)
        if not args.skip_build:
            subprocess.run(command + ["build"], cwd=ROOT, env=env, check=True)
        subprocess.run(command + ["up", "-d", "--wait", "--wait-timeout", "180"], cwd=ROOT, env=env, check=True)
        images = subprocess.check_output(command + ["images", "--format", "json"], cwd=ROOT, env=env, text=True)
        try:
            parsed = json.loads(images)
            report["images"] = parsed if isinstance(parsed, list) else [parsed]
        except json.JSONDecodeError:
            report["images"] = [json.loads(line) for line in images.splitlines() if line.strip()]
        subprocess.run(["npx", "playwright", "test", "--config", "playwright.week7.config.ts"],
                       cwd=ROOT / "frontend", env=env, check=True)
        report["passed"] = True
        report["browserStats"] = json.loads((ROOT / "frontend/test-results/week7/report.json").read_text())["stats"]
    finally:
        (evidence / "acceptance.json").write_text(json.dumps(report, indent=2) + "\n")
        if not args.keep_running:
            # Retain this project's named volumes. Never remove or reset the development database.
            subprocess.run(command + ["down"], cwd=ROOT, env=env, check=True)


if __name__ == "__main__":
    main()
