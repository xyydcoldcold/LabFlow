import hashlib
from pathlib import Path

import pytest

from labflow_worker.registry import capabilities, run_task


def test_registry_is_explicit_and_stable() -> None:
    assert capabilities() == ["demo.sleep_hash", "pyscf.single_point"]
    with pytest.raises(ValueError, match="Unsupported task type"):
        run_task("shell.command", "/tmp/input", {}, "bad", "test")


def test_demo_task_hashes_input_and_builds_reproducibility_manifest(tmp_path: Path) -> None:
    input_path = tmp_path / "h2.xyz"
    input_path.write_text("2\nhydrogen\nH 0 0 0\nH 0 0 0.74\n", encoding="utf-8")
    digest = hashlib.sha256(input_path.read_bytes()).hexdigest()

    result = run_task(
        "demo.sleep_hash", str(input_path), {"sleepSeconds": 0}, digest, "sha256:worker"
    )

    assert result["summary"]["sha256"] == digest
    assert result["manifest"]["environment"]["workerImageDigest"] == "sha256:worker"
    assert result["manifest"]["artifacts"][0]["sha256"] == digest


def test_demo_task_rejects_artifact_tampering(tmp_path: Path) -> None:
    input_path = tmp_path / "input.xyz"
    input_path.write_text("changed", encoding="utf-8")
    with pytest.raises(ValueError, match="checksum"):
        run_task("demo.sleep_hash", str(input_path), {}, "0" * 64, "test")
