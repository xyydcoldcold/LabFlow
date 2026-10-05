import json
import os
import socket
import traceback
from typing import Any

import pika

from .api import ApiError, LabFlowApi
from .executor import TaskExecutionError, execute_task
from .logs import AttemptLogUploader
from .lease import AttemptCancelled, AttemptLease, LeaseUnavailable
from .registry import capabilities

QUEUE = "labflow.jobs.q"


class Worker:
    def __init__(self) -> None:
        self.instance_name = os.getenv("WORKER_INSTANCE_NAME", socket.gethostname())
        self.image_digest = os.getenv("WORKER_IMAGE_DIGEST", "unknown")
        self.api = LabFlowApi(
            os.getenv("LABFLOW_API_URL", "http://localhost:8080"),
            _required("LABFLOW_SERVICE_TOKEN"),
        )
        registration = self.api.register(self.instance_name, self.image_digest, capabilities())
        self.worker_id = int(registration["workerId"])

    def run(self) -> None:
        credentials = pika.PlainCredentials(
            os.getenv("RABBITMQ_USERNAME", "labflow"),
            os.getenv("RABBITMQ_PASSWORD", "labflow_dev_password"),
        )
        connection = pika.BlockingConnection(pika.ConnectionParameters(
            host=os.getenv("RABBITMQ_HOST", "localhost"),
            port=int(os.getenv("RABBITMQ_PORT", "5672")),
            credentials=credentials,
            heartbeat=60,
            blocked_connection_timeout=60,
        ))
        self.connection = connection
        channel = connection.channel()
        channel.basic_qos(prefetch_count=1)
        channel.basic_consume(queue=QUEUE, on_message_callback=self._on_message, auto_ack=False)
        print(f"Worker {self.instance_name} registered as {self.worker_id}; waiting for jobs", flush=True)

        def heartbeat() -> None:
            try:
                self.api.heartbeat_worker(self.worker_id)
            except ApiError as error:
                print(f"Worker heartbeat failed: {error.code}", flush=True)
            connection.call_later(5, heartbeat)

        connection.call_later(5, heartbeat)
        try:
            channel.start_consuming()
        finally:
            if connection.is_open:
                connection.close()

    def _on_message(self, channel: Any, method: Any, properties: Any, body: bytes) -> None:
        del properties
        try:
            message = json.loads(body)
            if message.get("schemaVersion") != 1 or not isinstance(message.get("jobId"), int):
                raise ValueError("Unsupported job message")
            claim = self.api.claim(message["jobId"], self.worker_id)
        except ApiError as error:
            if error.status == 409:
                channel.basic_ack(delivery_tag=method.delivery_tag)
            elif 400 <= error.status < 500:
                channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
            else:
                channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
            return
        except (ValueError, json.JSONDecodeError):
            channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
            return

        attempt_id = int(claim["attemptId"])
        attempt_token = str(claim["attemptToken"])
        lease = AttemptLease(
            self.api, attempt_id, attempt_token,
            lambda: self.connection.process_data_events(time_limit=0),
        )
        logs = AttemptLogUploader(self.api, attempt_id, attempt_token)
        try:
            lease.tick()
            with logs:
                logs.write("SYSTEM", f"Claimed job {claim['jobId']} as attempt {claim['attemptNo']}\n")
                result = execute_task(claim, self.image_digest, logs.write, lease.tick)
            lease.tick(force=True)
            self.api.succeed(attempt_id, attempt_token, result)
        except AttemptCancelled:
            if not self._confirm_cancelled(attempt_id, attempt_token):
                channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
                return
        except LeaseUnavailable:
            # Do not report a task failure or success after losing ownership.
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
            return
        except Exception as error:
            if isinstance(error, ApiError) and error.code == "STALE_ATTEMPT":
                channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
                return
            if isinstance(error, ApiError) and error.code == "CANCEL_REQUESTED":
                if self._confirm_cancelled(attempt_id, attempt_token):
                    channel.basic_ack(delivery_tag=method.delivery_tag)
                else:
                    channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
                return
            failure = _failure(error)
            try:
                logs.write("SYSTEM", f"Task failed: {failure['code']}: {failure['message']}\n")
                logs.flush()
            except Exception:
                # A failed log upload must not prevent persisting the task failure.
                pass
            try:
                self.api.fail(attempt_id, attempt_token, failure)
            except ApiError as report_error:
                if report_error.code != "CANCEL_REQUESTED" or not self._confirm_cancelled(attempt_id, attempt_token):
                    channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
                    return
        channel.basic_ack(delivery_tag=method.delivery_tag)

    def _confirm_cancelled(self, attempt_id: int, attempt_token: str) -> bool:
        try:
            self.api.cancelled(attempt_id, attempt_token)
            return True
        except ApiError:
            return False


def _failure(error: Exception) -> dict[str, Any]:
    code = error.code if isinstance(error, TaskExecutionError) else "TASK_FAILED"
    return {
        "code": code,
        "message": str(error) or type(error).__name__,
        "type": type(error).__name__,
        "traceback": "".join(traceback.format_exception(error))[-8_000:],
    }


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} is required")
    return value


def main() -> None:
    Worker().run()
