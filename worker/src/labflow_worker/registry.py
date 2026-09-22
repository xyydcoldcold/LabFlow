from collections.abc import Callable
from typing import Any

from .tasks import run_demo_sleep_hash, run_pyscf_single_point

Task = Callable[[str, dict[str, Any], str, str], dict[str, Any]]

TASKS: dict[str, Task] = {
    "demo.sleep_hash": run_demo_sleep_hash,
    "pyscf.single_point": run_pyscf_single_point,
}


def capabilities() -> list[str]:
    return sorted(TASKS)


def run_task(
    task_type: str,
    input_path: str,
    spec: dict[str, Any],
    input_sha256: str,
    image_digest: str,
) -> dict[str, Any]:
    try:
        task = TASKS[task_type]
    except KeyError as error:
        raise ValueError(f"Unsupported task type: {task_type}") from error
    return task(input_path, spec, input_sha256, image_digest)
