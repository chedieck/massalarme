"""The producer path, driven against a local mock of the ontoplano contract.

Covers the scenarios that actually bite in production: partial success, a
response lost after the server stored the batch, a revoked token, rate limiting,
and a long offline stretch draining at once.
"""

from datetime import datetime, timedelta, timezone

import pytest

from mock_ontoplano import MockOntoplano
from ontoplano import (
    AuthError,
    HttpOntoplanoClient,
    OntoplanoConfig,
    PlanLimitError,
    Point,
    RateLimitedError,
    ServerError,
)
from store import WeighIn
from sync import SyncWorker

BASE_MOMENT = datetime(2026, 8, 9, 7, 12, 3, tzinfo=timezone.utc)


def weigh_in(index: int) -> WeighIn:
    return WeighIn(
        external_id=f"2026-08-09T07:12:0{index}Z-aaaa000{index}",
        captured_at=BASE_MOMENT + timedelta(days=index),
        weight_kg=70.0 + index,
        impedance=500.0 + index,
        raw_value=f"raw{index}",
        alarm_name="Wake up",
        source="phone",
    )


def seed(store, count: int) -> list[str]:
    weigh_ins = [weigh_in(i) for i in range(count)]
    store.upsert_weigh_ins(weigh_ins)
    return [w.external_id for w in weigh_ins]


def make_worker(store, client, **kwargs) -> SyncWorker:
    return SyncWorker(store, client, **kwargs)


# ── Happy path ───────────────────────────────────────────────────────


async def test_pending_readings_are_delivered_and_queue_empties(store, client, mock_server):
    ids = seed(store, 3)

    delivered = await make_worker(store, client).drain()

    assert delivered == 3
    assert store.pending_count() == 0
    assert mock_server.stored_ids() == set(ids)
    assert store.status()["last_success"] is not None


async def test_stream_is_declared_once_not_per_batch(store, client, mock_server):
    seed(store, 2)
    worker = make_worker(store, client)

    await worker.drain()
    store.upsert_weigh_ins([weigh_in(9)])
    await worker.drain()

    assert mock_server.declare_calls == 1


async def test_declaring_an_existing_stream_is_not_an_error(client, mock_server):
    assert await client.declare_stream() is True
    assert await client.declare_stream() is False


# ── Idempotency ──────────────────────────────────────────────────────


async def test_resending_an_accepted_reading_counts_as_success(store, client, mock_server):
    seed(store, 2)
    await make_worker(store, client).drain()

    # Force a resend of already-accepted points, as a crashed daemon would.
    store.set_state("last_success", None)
    with_conn = store._connect()
    with with_conn:
        with_conn.execute("UPDATE weigh_ins SET synced_at = NULL")
    with_conn.close()

    delivered = await make_worker(store, client).drain()

    assert delivered == 2
    assert store.pending_count() == 0
    # The server saw them twice but stored them once.
    assert mock_server.stored_count() == 2


async def test_response_lost_after_the_server_stored_the_batch(store, client, mock_server):
    """The failure the whole idempotency design exists for: the push succeeded
    server-side, but the client never saw the reply."""
    seed(store, 2)
    mock_server.drop_response_after_storing = 1

    worker = make_worker(store, client)
    await worker.drain()

    # Client thinks it failed, so the readings are still queued.
    assert store.pending_count() == 2

    await worker.drain()

    assert store.pending_count() == 0
    # Crucially: two readings in, two points stored. Not four.
    assert mock_server.stored_count() == 2


# ── Partial success ──────────────────────────────────────────────────


async def test_rejected_points_are_dropped_while_the_rest_are_stored(
    store, client, mock_server
):
    ids = seed(store, 3)
    mock_server.reject_ids = {ids[1]: "value out of range"}

    delivered = await make_worker(store, client).drain()

    assert delivered == 2
    assert store.pending_count() == 0
    assert store.status()["dropped"] == 1
    assert ids[1] not in mock_server.stored_ids()
    # The rejected reading is still in massalarme's own log.
    assert len(store.latest(limit=10)) == 3


async def test_a_dropped_point_is_never_retried(store, client, mock_server):
    ids = seed(store, 2)
    mock_server.reject_ids = {ids[0]: "malformed"}
    worker = make_worker(store, client)

    await worker.drain()
    mock_server.received_batches.clear()
    await worker.drain()

    assert mock_server.received_batches == []


# ── Error handling ───────────────────────────────────────────────────


async def test_revoked_token_halts_sync_and_keeps_data_queued(store, server_url):
    seed(store, 2)
    bad_client = HttpOntoplanoClient(
        OntoplanoConfig(enabled=True, base_url=server_url, token="wrong-token")
    )
    worker = make_worker(store, bad_client)

    try:
        await worker.drain()
    finally:
        await bad_client.close()

    assert worker.halted
    assert store.pending_count() == 2  # nothing lost
    assert "AuthError" in store.status()["last_error"]


async def test_a_halted_worker_stops_hitting_the_server(store, server_url, mock_server):
    seed(store, 1)
    bad_client = HttpOntoplanoClient(
        OntoplanoConfig(enabled=True, base_url=server_url, token="wrong-token")
    )
    worker = make_worker(store, bad_client)

    try:
        await worker.drain()
        calls_after_halt = mock_server.push_calls
        await worker.drain()
        await worker.drain()
    finally:
        await bad_client.close()

    assert mock_server.push_calls == calls_after_halt


async def test_plan_limit_halts_without_retrying(store, client, mock_server):
    seed(store, 1)
    mock_server.fail_pushes_with = [402]
    worker = make_worker(store, client)

    await worker.drain()

    assert worker.halted
    assert store.pending_count() == 1


async def test_rate_limiting_backs_off_and_honours_retry_after(store, client, mock_server):
    seed(store, 1)
    mock_server.fail_pushes_with = [429]
    mock_server.retry_after = "42"
    worker = make_worker(store, client)

    await worker.drain()

    assert not worker.halted
    assert store.pending_count() == 1
    assert worker._backoff == pytest.approx(42.0)


async def test_server_errors_keep_the_reading_queued_and_grow_the_backoff(
    store, client, mock_server
):
    seed(store, 1)
    mock_server.fail_pushes_with = [500, 503]
    worker = make_worker(store, client)

    await worker.drain()
    first_backoff = worker._backoff
    await worker.drain()

    assert first_backoff > 0
    assert worker._backoff > first_backoff
    assert store.pending_count() == 1

    # Recovers once the server does.
    await worker.drain()
    assert store.pending_count() == 0
    assert worker._backoff == 0


async def test_a_missing_stream_is_redeclared_then_the_push_retried(
    store, client, mock_server
):
    seed(store, 1)
    worker = make_worker(store, client)
    # A long-running daemon declared the stream at startup...
    await worker._ensure_stream()
    assert worker._stream_declared
    # ...and it was then deleted server-side, in the ontoplano UI.
    mock_server.streams.clear()

    # The push 404s; the worker re-declares and leaves the reading queued.
    await worker.drain()
    assert store.pending_count() == 1
    assert mock_server.declare_calls == 2

    # The next pass goes through.
    await worker.drain()
    assert store.pending_count() == 0


# ── Offline behaviour ────────────────────────────────────────────────


async def test_a_long_offline_stretch_drains_in_one_pass(store, client, mock_server):
    """Days of readings accumulated with no connectivity, then the phone comes
    home. Nothing may be dropped."""
    weigh_ins = [
        WeighIn(
            external_id=f"offline-{day:03d}",
            captured_at=BASE_MOMENT + timedelta(days=day),
            weight_kg=70.0 + (day % 5) * 0.1,
            impedance=500.0,
            raw_value=f"raw-{day}",
            alarm_name=None,
            source="phone",
        )
        for day in range(120)
    ]
    store.upsert_weigh_ins(weigh_ins)

    delivered = await make_worker(store, client).drain()

    assert delivered == 120
    assert store.pending_count() == 0
    assert mock_server.stored_count() == 120


async def test_batches_respect_the_configured_size(store, client, mock_server):
    seed(store, 7)

    await make_worker(store, client, batch_size=3).drain()

    assert [len(batch) for batch in mock_server.received_batches] == [3, 3, 1]
    assert store.pending_count() == 0


async def test_batch_larger_than_the_contract_limit_is_refused_locally(client):
    points = [
        Point(external_id=f"id-{i}", at="2026-08-09T07:12:03Z", value=70.0)
        for i in range(501)
    ]

    with pytest.raises(ValueError, match="exceeds"):
        await client.push_points(points)


async def test_disabled_sync_never_touches_the_network(store, mock_server):
    from ontoplano import DisabledClient

    seed(store, 3)
    worker = make_worker(store, DisabledClient(), enabled=False)

    assert await worker.drain() == 0
    assert mock_server.push_calls == 0
    assert store.pending_count() == 3


# ── Payload shape ────────────────────────────────────────────────────


async def test_points_carry_utc_timestamps_and_useful_meta(store, client, mock_server):
    seed(store, 1)

    await make_worker(store, client).drain()

    point = mock_server.received_batches[0][0]
    assert point["at"].endswith("Z")
    assert point["value"] == 70.0
    assert point["meta"]["source"] == "phone"
    assert point["meta"]["impedance_ohm"] == 500.0
    assert point["meta"]["alarm"] == "Wake up"


async def test_weight_only_readings_are_marked_as_such(store, client, mock_server):
    store.upsert_weigh_ins(
        [
            WeighIn(
                external_id="no-impedance",
                captured_at=BASE_MOMENT,
                weight_kg=73.9,
                impedance=None,
                raw_value="abc",
                alarm_name=None,
                source="phone",
            )
        ]
    )

    await make_worker(store, client).drain()

    point = mock_server.received_batches[0][0]
    assert point["meta"]["confidence"] == "weight_only"
    assert "impedance_ohm" not in point["meta"]


async def test_read_back_returns_what_was_pushed(store, client, mock_server):
    seed(store, 3)
    await make_worker(store, client).drain()

    points = await client.fetch_points(since="2026-08-01T00:00:00Z")

    assert len(points) == 3
    assert all(point["at"].endswith("Z") for point in points)
