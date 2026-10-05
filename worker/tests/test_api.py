import io
import json

from labflow_worker.api import LabFlowApi


def test_heartbeats_use_service_auth_and_bounded_http_timeout(monkeypatch) -> None:
    requests = []

    def urlopen(request, *, timeout):
        requests.append((request, timeout))
        return io.StringIO('{}')

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    api = LabFlowApi("http://backend:8080", "test-service-token")
    api.heartbeat_worker(7)
    api.heartbeat_attempt(9, "attempt-token")
    worker_request, worker_timeout = requests[0]
    attempt_request, attempt_timeout = requests[1]
    assert worker_request.full_url.endswith("/internal/workers/7/heartbeat")
    assert attempt_request.full_url.endswith("/internal/attempts/9/heartbeat")
    assert attempt_request.get_header("X-attempt-token") == "attempt-token"
    assert worker_request.get_header("Authorization") == "Bearer test-service-token"
    assert json.loads(attempt_request.data) == {}
    assert worker_timeout == attempt_timeout == 2
