from collections.abc import Iterable
from datetime import datetime, timezone
from typing import TypedDict


class HeartbeatPayload(TypedDict):
    instanceName: str
    sentAt: str
    capabilities: list[str]


def build_heartbeat(
    instance_name: str,
    capabilities: Iterable[str],
    *,
    sent_at: datetime | None = None,
) -> HeartbeatPayload:
    normalized_name = instance_name.strip()
    if not normalized_name:
        raise ValueError("instance_name must not be blank")

    timestamp = sent_at or datetime.now(timezone.utc)
    if timestamp.tzinfo is None or timestamp.utcoffset() is None:
        raise ValueError("sent_at must be timezone-aware")

    normalized_capabilities = sorted(
        {
            capability.strip()
            for capability in capabilities
            if capability.strip()
        }
    )

    return {
        "instanceName": normalized_name,
        "sentAt": timestamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "capabilities": normalized_capabilities,
    }
