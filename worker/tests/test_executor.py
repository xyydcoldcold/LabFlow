import hashlib
from pathlib import Path

import pytest

from labflow_worker.executor import TaskExecutionError, execute_task


def claim(input_path: Path, sleep_seconds: float, timeout_seconds: int) -> dict[str, object]:
    return {
        "taskType": "demo.sleep_hash",
        "inputPath": str(input_path),
        "inputSha256": hashlib.sha256(input_path.read_bytes()).hexdigest(),
        "spec": {
            "sleepSeconds": sleep_seconds,
            "timeoutSeconds": timeout_seconds,
            "maxMemoryMb": 1024,
        },
    }


def test_executor_runs_registered_task_without_mixing_result_and_logs(tmp_path: Path) -> None:
    input_path = tmp_path / "input.xyz"
    input_path.write_text("payload", encoding="utf-8")
    logs: list[tuple[str, str]] = []

    result = execute_task(claim(input_path, 0, 5), "test-image", lambda stream, text: logs.append((stream, text)))

    assert result["summary"]["sha256"] == hashlib.sha256(b"payload").hexdigest()
    assert any(stream == "STDOUT" and "sha256=" in text for stream, text in logs)


def test_executor_kills_timed_out_process_group(tmp_path: Path) -> None:
    input_path = tmp_path / "input.xyz"
    input_path.write_text("payload", encoding="utf-8")

    with pytest.raises(TaskExecutionError, match="exceeded") as raised:
        execute_task(claim(input_path, 2, 1), "test-image", lambda _stream, _text: None)

    assert raised.value.code == "TASK_TIMEOUT"
