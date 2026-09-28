from datetime import datetime, timezone
from threading import Event, Lock, Thread
from typing import Any

from .api import LabFlowApi


class AttemptLogUploader:
    def __init__(self, api: LabFlowApi, attempt_id: int, token: str) -> None:
        self.api = api
        self.attempt_id = attempt_id
        self.token = token
        self.sequence = 0
        self._lock = Lock()
        self._stream = "STDOUT"
        self._content = ""
        self._stopped = Event()
        self._thread: Thread | None = None
        self._error: Exception | None = None

    def __enter__(self) -> "AttemptLogUploader":
        self._thread = Thread(target=self._periodic_flush, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_args: Any) -> None:
        self._stopped.set()
        if self._thread is not None:
            self._thread.join()
        self.flush()

    def _periodic_flush(self) -> None:
        while not self._stopped.wait(0.5):
            try:
                self.flush()
            except Exception as error:
                self._error = error
                return

    def write(self, stream: str, content: str) -> None:
        with self._lock:
            if self._error is not None:
                raise self._error
            if self._content and self._stream != stream:
                self._flush_locked()
            self._stream = stream
            while content:
                available = 16_000 - len(self._content)
                self._content += content[:available]
                content = content[available:]
                if len(self._content) == 16_000:
                    self._flush_locked()

    def flush(self) -> None:
        with self._lock:
            if self._error is not None:
                raise self._error
            self._flush_locked()

    def _flush_locked(self) -> None:
        if not self._content:
            return
        payload: dict[str, Any] = {
            "seqNo": self.sequence,
            "stream": self._stream,
            "emittedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "content": self._content,
        }
        self.api.log(self.attempt_id, self.token, payload)
        self._content = ""
        self.sequence += 1
