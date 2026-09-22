import json
import time
import urllib.error
import urllib.request
from typing import Any


class ApiError(RuntimeError):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code


class LabFlowApi:
    def __init__(self, base_url: str, service_token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.service_token = service_token

    def register(self, instance_name: str, image_digest: str, capabilities: list[str]) -> dict[str, Any]:
        return self._request("/internal/workers/register", {
            "instanceName": instance_name,
            "imageDigest": image_digest,
            "capabilities": capabilities,
        })

    def claim(self, job_id: int, worker_id: int) -> dict[str, Any]:
        return self._request(f"/internal/jobs/{job_id}/claim", {"workerId": worker_id})

    def log(self, attempt_id: int, token: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            f"/internal/attempts/{attempt_id}/logs", payload,
            attempt_token=token, retries=5,
        )

    def succeed(self, attempt_id: int, token: str, result: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            f"/internal/attempts/{attempt_id}/succeed", result,
            attempt_token=token, retries=5,
        )

    def fail(self, attempt_id: int, token: str, error: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            f"/internal/attempts/{attempt_id}/fail", {"error": error},
            attempt_token=token, retries=5,
        )

    def _request(
        self,
        path: str,
        payload: dict[str, Any],
        *,
        attempt_token: str | None = None,
        retries: int = 2,
    ) -> dict[str, Any]:
        data = json.dumps(payload, separators=(",", ":")).encode()
        headers = {
            "Authorization": f"Bearer {self.service_token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if attempt_token:
            headers["X-Attempt-Token"] = attempt_token
        for attempt in range(retries + 1):
            request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method="POST")
            try:
                with urllib.request.urlopen(request, timeout=15) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                body: dict[str, Any] = {}
                try:
                    body = json.load(error)
                except (ValueError, OSError):
                    pass
                if error.code < 500 or attempt == retries:
                    raise ApiError(
                        error.code, str(body.get("code", "REQUEST_FAILED")),
                        str(body.get("message", error.reason)),
                    ) from error
            except (urllib.error.URLError, TimeoutError) as error:
                if attempt == retries:
                    raise ApiError(0, "NETWORK_ERROR", str(error)) from error
            time.sleep(min(0.25 * (2**attempt), 2))
        raise AssertionError("unreachable")
