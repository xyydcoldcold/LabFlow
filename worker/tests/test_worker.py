import json
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any

from labflow_worker.api import ApiError
from labflow_worker.worker import Worker


class Channel:
    def __init__(self) -> None:
        self.acked: list[int] = []
        self.rejected: list[tuple[int, bool]] = []
        self.nacked: list[tuple[int, bool]] = []

    def basic_ack(self, *, delivery_tag: int) -> None:
        self.acked.append(delivery_tag)

    def basic_reject(self, *, delivery_tag: int, requeue: bool) -> None:
        self.rejected.append((delivery_tag, requeue))

    def basic_nack(self, *, delivery_tag: int, requeue: bool) -> None:
        self.nacked.append((delivery_tag, requeue))


class SuccessfulApi:
    def __init__(self) -> None:
        self.logs: list[dict[str, Any]] = []
        self.result: dict[str, Any] | None = None

    def claim(self, job_id: int, worker_id: int) -> dict[str, Any]:
        assert (job_id, worker_id) == (42, 7)
        return {"jobId": 42, "attemptId": 9, "attemptNo": 1, "attemptToken": "token"}

    def heartbeat_attempt(self, attempt_id: int, token: str) -> dict[str, Any]:
        assert (attempt_id, token) == (9, "token")
        return {"leaseExpiresAt": (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat()}

    def log(self, attempt_id: int, token: str, payload: dict[str, Any]) -> dict[str, Any]:
        self.logs.append(payload)
        return payload

    def succeed(self, attempt_id: int, token: str, result: dict[str, Any]) -> dict[str, Any]:
        assert (attempt_id, token) == (9, "token")
        assert self.logs, "Logs must be flushed before committing the terminal result"
        self.result = result
        return {"status": "SUCCEEDED"}

    def fail(self, attempt_id: int, token: str, error: dict[str, Any]) -> dict[str, Any]:
        raise AssertionError(f"unexpected failure: {error}")


def bare_worker(api: Any) -> Worker:
    worker = Worker.__new__(Worker)
    worker.api = api
    worker.worker_id = 7
    worker.image_digest = "sha256:test"
    worker.instance_name = "worker-test"
    worker.connection = SimpleNamespace(process_data_events=lambda **_kwargs: None)
    return worker


def test_consumer_acknowledges_only_after_terminal_result_is_confirmed(monkeypatch: Any) -> None:
    api = SuccessfulApi()
    worker = bare_worker(api)
    channel = Channel()
    expected = {"summary": {"energyHartree": -1.0}, "manifest": {}}
    monkeypatch.setattr("labflow_worker.worker.execute_task", lambda *_args: expected)

    worker._on_message(
        channel, SimpleNamespace(delivery_tag=5), None,
        json.dumps({"jobId": 42, "eventId": 10, "schemaVersion": 1}).encode(),
    )

    assert api.result == expected
    assert channel.acked == [5]
    assert channel.nacked == []
    assert api.logs[0]["stream"] == "SYSTEM"


def test_consumer_acks_duplicate_claim_and_dead_letters_invalid_job() -> None:
    class ErrorApi:
        def __init__(self, status: int) -> None:
            self.status = status

        def claim(self, _job_id: int, _worker_id: int) -> dict[str, Any]:
            raise ApiError(self.status, "ERROR", "claim rejected")

    duplicate_channel = Channel()
    bare_worker(ErrorApi(409))._on_message(
        duplicate_channel, SimpleNamespace(delivery_tag=6), None,
        b'{"jobId":42,"eventId":10,"schemaVersion":1}',
    )
    assert duplicate_channel.acked == [6]

    missing_channel = Channel()
    bare_worker(ErrorApi(404))._on_message(
        missing_channel, SimpleNamespace(delivery_tag=7), None,
        b'{"jobId":42,"eventId":10,"schemaVersion":1}',
    )
    assert missing_channel.rejected == [(7, False)]


def test_consumer_does_not_run_or_report_result_when_lease_is_stale(monkeypatch: Any) -> None:
    class StaleApi(SuccessfulApi):
        def heartbeat_attempt(self, *_args: Any) -> dict[str, Any]:
            raise ApiError(409, "STALE_ATTEMPT", "stale")

    api = StaleApi()
    channel = Channel()
    def unexpected(*_args: Any) -> None:
        raise AssertionError("A stale attempt must not start a child task")
    monkeypatch.setattr("labflow_worker.worker.execute_task", unexpected)
    bare_worker(api)._on_message(channel, SimpleNamespace(delivery_tag=8), None,
                                b'{"jobId":42,"schemaVersion":1}')
    assert channel.nacked == [(8, True)]
    assert channel.acked == []
    assert api.result is None
    assert api.logs == []


def test_consumer_stops_without_reporting_terminal_state_after_lease_loss(monkeypatch: Any) -> None:
    from labflow_worker.lease import LeaseUnavailable
    api = SuccessfulApi()
    channel = Channel()
    def lose_lease(*_args: Any) -> None:
        raise LeaseUnavailable("renewal unavailable")
    monkeypatch.setattr("labflow_worker.worker.execute_task", lose_lease)
    bare_worker(api)._on_message(channel, SimpleNamespace(delivery_tag=9), None,
                                b'{"jobId":42,"schemaVersion":1}')
    assert channel.nacked == [(9, True)]
    assert channel.acked == []
    assert api.result is None


def test_idle_worker_sends_and_reschedules_heartbeat(monkeypatch: Any) -> None:
    heartbeats = []
    timers = []
    connection = SimpleNamespace(is_open=True)
    connection.call_later = lambda delay, callback: timers.append((delay, callback))
    connection.close = lambda: setattr(connection, "is_open", False)
    channel = SimpleNamespace(
        basic_qos=lambda **_kwargs: None,
        basic_consume=lambda **_kwargs: None,
        start_consuming=lambda: timers.pop(0)[1](),
    )
    connection.channel = lambda: channel
    monkeypatch.setattr("labflow_worker.worker.pika.BlockingConnection", lambda *_args: connection)
    api = SimpleNamespace(heartbeat_worker=lambda worker_id: heartbeats.append(worker_id))
    bare_worker(api).run()
    assert heartbeats == [7]
    assert len(timers) == 1
    assert timers[0][0] == 5
    assert not connection.is_open
