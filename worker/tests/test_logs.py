from typing import Any

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

    assert [payload["seqNo"] for payload in api.payloads] == [0, 1, 2]
    assert [len(payload["content"]) for payload in api.payloads] == [16_000, 1, 8]
    assert api.payloads[2]["stream"] == "STDERR"
