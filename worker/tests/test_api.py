import io
import json
import uuid
import urllib.error

from labflow_worker.api import LabFlowApi


def test_transport_retries_keep_request_id_and_next_operation_gets_a_new_one(monkeypatch) -> None:
    requests = []
    def urlopen(request, *, timeout):
        requests.append(request)
        if len(requests) == 1:
            raise urllib.error.URLError("injected network failure")
        return io.StringIO('{"workerId":7}')
    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    monkeypatch.setattr("time.sleep", lambda _: None)
    api = LabFlowApi("http://backend:8080", "test-service-token")
    api.register("worker", "test", ["demo.sleep_hash"])
    first_id = requests[0].get_header("X-request-id")
    assert str(uuid.UUID(first_id)) == first_id
    assert requests[1].get_header("X-request-id") == first_id == api.last_request_id
    api.heartbeat_worker(7)
    assert requests[2].get_header("X-request-id") != first_id


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


def test_cancellation_confirmation_uses_attempt_token(monkeypatch) -> None:
    requests = []
    def urlopen(request, *, timeout):
        requests.append(request)
        return io.StringIO('{"status":"CANCELLED"}')
    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    LabFlowApi("http://backend:8080", "test").cancelled(9, "token")
    assert requests[0].full_url.endswith("/internal/attempts/9/cancelled")
    assert requests[0].get_header("X-attempt-token") == "token"
