"""Run real SIGKILL takeover acceptance on a separately named Docker Compose stack."""
import argparse
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "tests/fault-injection/compose.yml"
SERVICE_TOKEN = "labflow-local-worker-service-token-change-me"


def timestamp(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def request(base, path, token=None, body=None, headers=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Content-Type": "application/json", **(headers or {})}
    if token: h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, headers=h, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def wait_for(fn, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = fn()
        if result: return result
        time.sleep(0.1)
    raise TimeoutError("Acceptance condition timed out")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Dedicated project name starting with labflow-w5-")
    parser.add_argument("--rounds", type=int, default=20)
    parser.add_argument("--base-url", default="http://127.0.0.1:18085")
    parser.add_argument("--output", type=Path, default=ROOT / "test-results/week5-crash.json")
    args = parser.parse_args()
    if not args.project.startswith("labflow-w5-") or args.rounds < 1:
        parser.error("Use a dedicated labflow-w5-* project and a positive round count")
    command = ["docker", "compose", "-p", args.project, "-f", str(COMPOSE)]

    def compose(*parts):
        return subprocess.run(command + list(parts), check=True, capture_output=True, text=True).stdout.strip()

    def sql(query):
        output = compose("exec", "-T", "postgres", "psql", "-U", "labflow", "-d", "labflow", "-At", "-c", query)
        return output

    base = args.base_url
    email = "week5-" + uuid.uuid4().hex + "@example.com"
    auth = request(base, "/api/auth/register", body={"email": email, "password": "week5-test-password", "displayName": "Week 5 acceptance"})
    token = auth["accessToken"]
    project = request(base, "/api/projects", token, {"name": "Week 5 crash acceptance"})["id"]
    xyz = b"2\nHydrogen\nH 0 0 0\nH 0 0 0.74\n"
    boundary = "labflow" + uuid.uuid4().hex
    raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="h2.xyz"\r\nContent-Type: chemical/x-xyz\r\n\r\n'.encode()
           + xyz + f'\r\n--{boundary}--\r\n'.encode())
    input_id = request(base, f"/api/projects/{project}/inputs", token, raw=raw,
                       headers={"Content-Type": "multipart/form-data; boundary=" + boundary})["id"]
    config_id = request(base, f"/api/projects/{project}/configs", token, {"name": "Crash demo", "spec": {
        "schemaVersion": 1, "taskType": "demo.sleep_hash", "sleepSeconds": 3, "maxMemoryMb": 1024, "timeoutSeconds": 30}})["id"]
    results = []
    report = {"project": args.project, "dateUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
              "environment": {"leaseSeconds": 5, "scanSeconds": 1, "outboxSeconds": 0.2, "retryTtlSeconds": [15, 60, 300]},
              "iterations": results, "passed": False}
    report["environment"]["imageIds"] = {
        service: subprocess.run(["docker", "inspect", "--format", "{{.Image}}", compose("ps", "-q", service)],
                                check=True, capture_output=True, text=True).stdout.strip()
        for service in ("backend", "postgres", "rabbitmq")
    }
    report["environment"]["dockerCpuCountAndMemoryBytes"] = subprocess.run(
        ["docker", "info", "--format", "{{.NCPU}} {{.MemTotal}}"], check=True, capture_output=True, text=True).stdout.strip()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        for index in range(args.rounds):
            compose("stop", "-t", "1", "worker-a", "worker-b")
            compose("up", "-d", "--no-deps", "worker-a")
            job_id = request(base, "/api/jobs", token, {"projectId": project, "molecularInputId": input_id,
                             "experimentConfigId": config_id}, {"Idempotency-Key": uuid.uuid4().hex})["id"]
            def details(): return request(base, f"/api/jobs/{job_id}", token)
            first = wait_for(lambda: (j if (j := details())["status"] == "RUNNING" else None))
            old = first["attempts"][0]
            assert old["workerInstance"] == "worker-a"
            row = json.loads(sql(f"SELECT row_to_json(t) FROM (SELECT a.attempt_token, w.last_heartbeat_at FROM job_attempts a JOIN workers w ON w.id=a.worker_id WHERE a.id={int(old['id'])}) t"))
            compose("kill", "-s", "SIGKILL", "worker-a")
            killed = time.time()
            compose("up", "-d", "--no-deps", "worker-b")
            finished = wait_for(lambda: (j if (j := details())["status"] == "SUCCEEDED" else None))
            succeeded = time.time()
            attempts = finished["attempts"]
            assert [a["status"] for a in attempts] == ["LOST", "SUCCEEDED"], attempts
            assert attempts[1]["workerInstance"] == "worker-b"
            assert int(sql(f"SELECT count(*) FROM job_results WHERE job_id={int(job_id)}")) == 1
            assert finished["result"]["summary"]["sha256"] == hashlib.sha256(xyz).hexdigest()
            for action, body in (("heartbeat", {}), ("logs", {"seqNo": 999, "stream": "SYSTEM", "content": "stale", "emittedAt": dt.datetime.now(dt.timezone.utc).isoformat()}),
                                 ("succeed", {"summary": {}, "manifest": {}})):
                try:
                    request(base, f"/internal/attempts/{old['id']}/{action}", SERVICE_TOKEN, body,
                            {"X-Attempt-Token": row["attempt_token"]})
                    raise AssertionError("Stale attempt accepted")
                except urllib.error.HTTPError as error:
                    assert error.code == 409 and json.load(error)["code"] == "STALE_ATTEMPT"
            result = {"iteration": index + 1, "jobId": job_id, "resultRows": 1,
                      "lastHeartbeatToNewAttemptSeconds": timestamp(attempts[1]["startedAt"]) - timestamp(row["last_heartbeat_at"]),
                      "lastHeartbeatToLostSeconds": timestamp(attempts[0]["finishedAt"]) - timestamp(row["last_heartbeat_at"]),
                      "killToNewAttemptSeconds": timestamp(attempts[1]["startedAt"]) - killed,
                      "killToSuccessSeconds": succeeded - killed,
                      "killToLostSeconds": timestamp(attempts[0]["finishedAt"]) - killed}
            results.append(result)
            args.output.write_text(json.dumps(report, indent=2))
            print(f"Round {index + 1}/{args.rounds}: LOST + SUCCEEDED, one result; takeover {result['killToNewAttemptSeconds']:.2f}s", flush=True)
        summary = {}
        for key in ("lastHeartbeatToNewAttemptSeconds", "killToNewAttemptSeconds", "killToSuccessSeconds", "killToLostSeconds", "lastHeartbeatToLostSeconds"):
            values = sorted(r[key] for r in results)
            summary[key] = {"median": statistics.median(values), "p95": values[math.ceil(len(values)*.95)-1]}
        report["summary"] = summary
        report["gates"] = {"atLeast20Runs": len(results) >= 20, "zeroDuplicateResults": all(r["resultRows"] == 1 for r in results),
                           "detectionP95Under20s": summary["lastHeartbeatToLostSeconds"]["p95"] < 20,
                           "takeoverP95Under30s": summary["killToNewAttemptSeconds"]["p95"] < 30}
        report["passed"] = all(report["gates"].values())
        print(json.dumps({"passed": report["passed"], "summary": summary}, indent=2), flush=True)
    finally:
        args.output.write_text(json.dumps(report, indent=2))
        compose("stop", "-t", "1", "worker-a", "worker-b")
    if not report["passed"]: raise SystemExit(1)


if __name__ == "__main__": main()
