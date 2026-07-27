from datetime import datetime, timezone

import pytest

from labflow_worker import build_heartbeat


def test_build_heartbeat_returns_stable_payload() -> None:
    heartbeat = build_heartbeat(
        " worker-a ",
        ["demo.sleep_hash", "pyscf.single_point", "demo.sleep_hash"],
        sent_at=datetime(2026, 7, 25, 12, 30, tzinfo=timezone.utc),
    )

    assert heartbeat == {
        "instanceName": "worker-a",
        "sentAt": "2026-07-25T12:30:00Z",
        "capabilities": ["demo.sleep_hash", "pyscf.single_point"],
    }


def test_build_heartbeat_rejects_blank_instance_name() -> None:
    with pytest.raises(ValueError, match="instance_name must not be blank"):
        build_heartbeat("  ", [])


def test_build_heartbeat_rejects_naive_timestamp() -> None:
    with pytest.raises(ValueError, match="sent_at must be timezone-aware"):
        build_heartbeat("worker-a", [], sent_at=datetime(2026, 7, 25, 12, 30))
