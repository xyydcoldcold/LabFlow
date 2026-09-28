from typing import Any
from threading import Event

import pytest

from labflow_worker.logs import AttemptLogUploader


class RecordingApi:
    def __init__(self) -> None:
        self.payloads: list[dict[str, Any]] = []

    def log(self, attempt_id: int, token: str, payload: dict[str, Any]) -> dict[str, Any]:
        assert attempt_id == 9
        assert token == "attempt-token"
        self.payloads.append(payload)
        return payload


def test_log_uploader_chunks_output_and_assigns_global_sequence_numbers() -> None:
    api = RecordingApi()
    uploader = AttemptLogUploader(api, 9, "attempt-token")  # type: ignore[arg-type]
    uploader.write("STDOUT", "a" * 16_001)
    uploader.write("STDERR", "failure\n")
    uploader.flush()

    assert [payload["seqNo"] for payload in api.payloads] == [0, 1, 2]
    assert [len(payload["content"]) for payload in api.payloads] == [16_000, 1, 8]
    assert api.payloads[2]["stream"] == "STDERR"


def test_log_uploader_batches_lines_and_flushes_before_context_exit() -> None:
    api = RecordingApi()
    with AttemptLogUploader(api, 9, "attempt-token") as uploader:  # type: ignore[arg-type]
        uploader.write("STDOUT", "first\n")
        uploader.write("STDOUT", "second\n")
        assert api.payloads == []
    assert [payload["content"] for payload in api.payloads] == ["first\nsecond\n"]


def test_log_uploader_flushes_quiet_output_while_task_is_running() -> None:
    received = Event()

    class LiveApi(RecordingApi):
        def log(self, attempt_id: int, token: str, payload: dict[str, Any]) -> dict[str, Any]:
            result = super().log(attempt_id, token, payload)
            received.set()
            return result

    api = LiveApi()
    with AttemptLogUploader(api, 9, "attempt-token") as uploader:  # type: ignore[arg-type]
        uploader.write("STDOUT", "running\n")
        assert received.wait(2), "Quiet output was not flushed during execution"


def test_failed_flush_retains_sequence_and_content_for_retry() -> None:
    class FlakyApi(RecordingApi):
        def log(self, attempt_id: int, token: str, payload: dict[str, Any]) -> dict[str, Any]:
            if not self.payloads:
                self.payloads.append(payload.copy())
                raise RuntimeError("temporary failure")
            return super().log(attempt_id, token, payload)

    api = FlakyApi()
    uploader = AttemptLogUploader(api, 9, "attempt-token")  # type: ignore[arg-type]
    uploader.write("STDOUT", "retained\n")
    with pytest.raises(RuntimeError, match="temporary"):
        uploader.flush()
    uploader.flush()
    assert [payload["seqNo"] for payload in api.payloads] == [0, 0]
    assert api.payloads[0]["content"] == api.payloads[1]["content"]
