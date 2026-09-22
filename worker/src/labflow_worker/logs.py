from datetime import datetime, timezone
from threading import Lock
from typing import Any

from .api import LabFlowApi


class AttemptLogUploader:
    def __init__(self, api: LabFlowApi, attempt_id: int, token: str) -> None:
        self.api = api
        self.attempt_id = attempt_id
        self.token = token
        self.sequence = 0
        self._lock = Lock()

    def write(self, stream: str, content: str) -> None:
        with self._lock:
            while content:
                chunk, content = content[:16_000], content[16_000:]
                payload: dict[str, Any] = {
                    "seqNo": self.sequence,
                    "stream": stream,
                    "emittedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                    "content": chunk,
                }
                self.api.log(self.attempt_id, self.token, payload)
                self.sequence += 1
