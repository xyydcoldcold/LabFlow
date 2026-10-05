import time
from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any

from .api import ApiError, LabFlowApi


class LeaseUnavailable(RuntimeError):
    """Execution must stop when ownership cannot be confirmed."""


class AttemptLease:
    def __init__(
        self, api: LabFlowApi, attempt_id: int, token: str,
        pump: Callable[[], None], *,
        clock: Callable[[], float] = time.monotonic,
        wall_clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ) -> None:
        self.api = api
        self.attempt_id = attempt_id
        self.token = token
        self.pump = pump
        self.clock = clock
        self.wall_clock = wall_clock
        self.next_heartbeat = clock()

    def tick(self) -> None:
        self.pump()
        if self.clock() < self.next_heartbeat:
            return
        try:
            response: dict[str, Any] = self.api.heartbeat_attempt(self.attempt_id, self.token)
            expires = datetime.fromisoformat(response["leaseExpiresAt"].replace("Z", "+00:00"))
            remaining = (expires - self.wall_clock()).total_seconds()
            if remaining <= 0:
                raise ValueError("Backend returned an expired lease")
        except (ApiError, ValueError, KeyError, TypeError) as error:
            raise LeaseUnavailable("Attempt lease renewal failed; stopping task") from error
        self.next_heartbeat = self.clock() + min(5.0, remaining / 3)
