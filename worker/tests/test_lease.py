from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from labflow_worker.api import ApiError
from labflow_worker.lease import AttemptLease, LeaseUnavailable


def test_lease_renews_every_five_seconds_and_keeps_broker_pumping() -> None:
    now = datetime(2026, 10, 5, tzinfo=timezone.utc)
    elapsed = [0.0]
    renewals = []
    pumps = []

    def renew(attempt_id, token):
        renewals.append((attempt_id, token))
        return {"leaseExpiresAt": (now + timedelta(seconds=elapsed[0] + 30)).isoformat()}

    lease = AttemptLease(SimpleNamespace(heartbeat_attempt=renew), 9, "token",
                         lambda: pumps.append(elapsed[0]), clock=lambda: elapsed[0],
                         wall_clock=lambda: now + timedelta(seconds=elapsed[0]))
    for instant in (0, 1, 4.9, 5, 10):
        elapsed[0] = instant
        lease.tick()
    assert len(pumps) == 5
    assert renewals == [(9, "token")] * 3


@pytest.mark.parametrize("status", [0, 409, 503])
def test_lease_stops_execution_when_renewal_is_unavailable(status) -> None:
    def renew(*_args):
        raise ApiError(status, "STALE_ATTEMPT", "unavailable")

    lease = AttemptLease(SimpleNamespace(heartbeat_attempt=renew), 9, "token", lambda: None)
    with pytest.raises(LeaseUnavailable):
        lease.tick()


def test_short_lease_renews_before_expiry() -> None:
    now = datetime(2026, 10, 5, tzinfo=timezone.utc)
    elapsed = [0.0]
    renewed = []

    def renew(*_args):
        renewed.append(elapsed[0])
        return {"leaseExpiresAt": (now + timedelta(seconds=elapsed[0] + 3)).isoformat()}

    lease = AttemptLease(SimpleNamespace(heartbeat_attempt=renew), 9, "token", lambda: None,
                         clock=lambda: elapsed[0],
                         wall_clock=lambda: now + timedelta(seconds=elapsed[0]))
    lease.tick()
    elapsed[0] = 1
    lease.tick()
    assert renewed == [0, 1]


@pytest.mark.parametrize("expiry", ["invalid", "2026-10-05T00:00:00Z", "2026-10-05T12:00:00"])
def test_invalid_or_expired_lease_response_stops_execution(expiry) -> None:
    api = SimpleNamespace(heartbeat_attempt=lambda *_: {"leaseExpiresAt": expiry})
    lease = AttemptLease(api, 9, "token", lambda: None,
                         wall_clock=lambda: datetime(2026, 10, 5, 12, tzinfo=timezone.utc))
    with pytest.raises(LeaseUnavailable):
        lease.tick()
