import json
import os
import resource
import signal
import subprocess
import sys
import tempfile
import threading
from collections.abc import Callable
from pathlib import Path
from typing import Any

LogCallback = Callable[[str, str], None]


class TaskExecutionError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def execute_task(
    claim: dict[str, Any],
    image_digest: str,
    on_log: LogCallback,
) -> dict[str, Any]:
    spec = claim["spec"]
    timeout_seconds = int(spec.get("timeoutSeconds", 300))
    memory_mb = int(spec.get("maxMemoryMb", 1024))
    input_path = claim["inputArtifactPath"] if "inputArtifactPath" in claim else claim["inputPath"]
    request = {
        "taskType": claim["taskType"],
        "inputPath": input_path,
        "inputSha256": claim["inputSha256"],
        "spec": spec,
        "imageDigest": image_digest,
    }
    with tempfile.TemporaryDirectory(prefix="labflow-task-") as directory:
        result_path = Path(directory) / "result.json"
        command = [sys.executable, "-m", "labflow_worker.task_process", "--result", str(result_path)]
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            shell=False,
            cwd=directory,
            env=_task_environment(),
            start_new_session=True,
            preexec_fn=_resource_limits(memory_mb, timeout_seconds),
        )
        assert process.stdin is not None
        process.stdin.write(json.dumps(request, separators=(",", ":")))
        process.stdin.close()
        reader_errors: list[BaseException] = []
        readers = [
            threading.Thread(
                target=_read_stream,
                args=(process.stdout, "STDOUT", on_log, reader_errors),
                daemon=True,
            ),
            threading.Thread(
                target=_read_stream,
                args=(process.stderr, "STDERR", on_log, reader_errors),
                daemon=True,
            ),
        ]
        for reader in readers:
            reader.start()
        try:
            return_code = process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired as error:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise TaskExecutionError("TASK_TIMEOUT", f"Task exceeded {timeout_seconds} seconds") from error
        finally:
            for reader in readers:
                reader.join(timeout=5)

        if return_code != 0:
            code = "RESOURCE_LIMIT_EXCEEDED" if return_code in (-signal.SIGKILL, -signal.SIGSEGV) else "TASK_PROCESS_FAILED"
            raise TaskExecutionError(code, f"Task process exited with status {return_code}")
        if reader_errors:
            raise TaskExecutionError("LOG_UPLOAD_FAILED", str(reader_errors[0])) from reader_errors[0]
        if not result_path.is_file():
            raise TaskExecutionError("RESULT_MISSING", "Task completed without a result document")
        result = json.loads(result_path.read_text(encoding="utf-8"))
        if not isinstance(result.get("summary"), dict) or not isinstance(result.get("manifest"), dict):
            raise TaskExecutionError("RESULT_INVALID", "Task returned an invalid result document")
        return result


def _read_stream(
    stream: Any,
    name: str,
    callback: LogCallback,
    errors: list[BaseException],
) -> None:
    if stream is None:
        return
    for line in iter(stream.readline, ""):
        if line:
            if not errors:
                try:
                    callback(name, line)
                except BaseException as error:
                    errors.append(error)
    stream.close()


def _task_environment() -> dict[str, str]:
    return {
        "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
        "PYTHONPATH": os.environ.get("PYTHONPATH", ""),
        "PYTHONUNBUFFERED": "1",
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "OMP_NUM_THREADS": os.environ.get("LABFLOW_TASK_THREADS", "1"),
        "OPENBLAS_NUM_THREADS": os.environ.get("LABFLOW_TASK_THREADS", "1"),
    }


def _resource_limits(memory_mb: int, timeout_seconds: int) -> Callable[[], None]:
    def apply() -> None:
        # RLIMIT_AS is enforced by the Linux worker image. Darwin rejects a
        # useful address-space limit before exec because of its shared-cache mapping.
        if sys.platform.startswith("linux"):
            memory_bytes = memory_mb * 1024 * 1024
            resource.setrlimit(resource.RLIMIT_AS, (memory_bytes, memory_bytes))
        cpu_seconds = max(1, timeout_seconds)
        resource.setrlimit(resource.RLIMIT_CPU, (cpu_seconds, cpu_seconds + 1))
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))

    return apply
