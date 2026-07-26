import json
import os
import socket
import time

from .heartbeat import build_heartbeat


def main() -> None:
    instance_name = os.getenv("WORKER_INSTANCE_NAME", socket.gethostname())
    capabilities = os.getenv("WORKER_CAPABILITIES", "").split(",")
    interval_seconds = _heartbeat_interval_seconds()

    while True:
        heartbeat = build_heartbeat(instance_name, capabilities)
        print(json.dumps(heartbeat, separators=(",", ":"), sort_keys=True), flush=True)
        time.sleep(interval_seconds)


def _heartbeat_interval_seconds() -> float:
    raw_interval = os.getenv("WORKER_HEARTBEAT_INTERVAL_SECONDS", "10")

    try:
        interval_seconds = float(raw_interval)
    except ValueError as error:
        raise ValueError(
            "WORKER_HEARTBEAT_INTERVAL_SECONDS must be a number"
        ) from error

    if interval_seconds <= 0:
        raise ValueError("WORKER_HEARTBEAT_INTERVAL_SECONDS must be greater than zero")

    return interval_seconds


if __name__ == "__main__":
    main()
