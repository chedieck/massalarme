"""Sessionisation and identity — the parts that, if wrong, silently corrupt the
user's weight chart with duplicate points."""

from datetime import datetime, timedelta, timezone

import pytest

from store import (
    Measurement,
    WeighIn,
    make_external_id,
    parse_timestamp,
    sessionise,
    utc_iso,
)

# One real burst from the shipped database: three distinct measurements as the
# user settles onto the scale, each rebroadcast many times.
BURST = [
    ("2026-03-29T10:01:28.217515", 71.35, "0226b207020e0a1e1f4102be37"),
    ("2026-03-29T10:01:29.173059", 71.35, "0226b207020e0a1e1f4102be37"),
    ("2026-03-29T10:01:32.386097", 71.35, "0226b207020e0a1e1f4102be37"),
    ("2026-03-29T10:01:43.026509", 71.5, "0226b207020e0a1e2e3f02dc37"),
    ("2026-03-29T10:01:44.942033", 71.5, "0226b207020e0a1e2e3f02dc37"),
    ("2026-03-29T10:02:06.975585", 71.55, "0226b207020e0a1f0a3f02e637"),
    ("2026-03-29T10:02:10.241776", 71.55, "0226b207020e0a1f0a3f02e637"),
]


def measurements_from(rows):
    return [
        Measurement(
            captured_at=parse_timestamp(timestamp), weight_kg=weight, raw_value=raw
        )
        for timestamp, weight, raw in rows
    ]


def test_one_trip_to_the_scale_becomes_one_weigh_in():
    weigh_ins = sessionise(measurements_from(BURST))

    assert len(weigh_ins) == 1
    # The scale is still settling until the last payload, so that is the reading.
    assert weigh_ins[0].weight_kg == 71.55
    assert weigh_ins[0].measurement_count == 3


def test_separate_trips_stay_separate():
    morning = BURST
    evening = [
        ("2026-03-29T19:00:00.000000", 72.1, "0226b207020e13000041020838"),
    ]
    weigh_ins = sessionise(measurements_from(morning + evening))

    assert len(weigh_ins) == 2
    assert {round(w.weight_kg, 2) for w in weigh_ins} == {71.55, 72.1}


def test_sessionise_is_deterministic_regardless_of_input_order():
    forward = sessionise(measurements_from(BURST))
    backward = sessionise(measurements_from(list(reversed(BURST))))

    assert [w.external_id for w in forward] == [w.external_id for w in backward]


def test_external_id_is_a_pure_function_of_the_reading():
    moment = datetime(2026, 8, 9, 7, 12, 3, tzinfo=timezone.utc)

    first = make_external_id(moment, "0226b2", 78.4)
    second = make_external_id(moment, "0226b2", 78.4)

    assert first == second
    assert first.startswith("2026-08-09T07:12:03Z")


def test_identical_payloads_on_different_days_get_different_ids():
    """Rows 55 and 56 of the shipped database are byte-identical payloads from
    different days. A content-only hash would collapse them into one point."""
    payload = "02a4b207010e161133fdff7c38"

    july_23 = make_external_id(datetime(2026, 7, 23, 11, 30, 41, tzinfo=timezone.utc), payload, 72.3)
    july_24 = make_external_id(datetime(2026, 7, 24, 11, 30, 29, tzinfo=timezone.utc), payload, 72.3)

    assert july_23 != july_24


def test_sub_second_jitter_does_not_change_the_id():
    """Retries must not mint new ids because of microsecond drift."""
    base = datetime(2026, 8, 9, 7, 12, 3, 0, tzinfo=timezone.utc)
    jittered = base.replace(microsecond=987654)

    assert make_external_id(base, "abc", 78.4) == make_external_id(jittered, "abc", 78.4)


def test_naive_timestamps_are_treated_as_local_and_normalised_to_utc():
    naive = parse_timestamp("2026-07-29T08:30:30.173696")
    assert naive.tzinfo is None

    rendered = utc_iso(naive)
    assert rendered.endswith("Z")
    # Whatever the machine's zone, the rendered form must round-trip.
    assert parse_timestamp(rendered).tzinfo is not None


def test_empty_input_is_not_an_error():
    assert sessionise([]) == []


# ── Store behaviour ──────────────────────────────────────────────────


def make_weigh_in(external_id="id-1", weight=70.0, moment=None) -> WeighIn:
    moment = moment or datetime(2026, 8, 9, 7, 0, 0, tzinfo=timezone.utc)
    return WeighIn(
        external_id=external_id,
        captured_at=moment,
        weight_kg=weight,
        impedance=500.0,
        raw_value="deadbeef",
        alarm_name="Wake up",
        source="phone",
    )


def test_upsert_ignores_readings_already_present(store):
    assert store.upsert_weigh_ins([make_weigh_in()]) == (1, 0)
    assert store.upsert_weigh_ins([make_weigh_in()]) == (0, 1)
    assert store.pending_count() == 1


def test_marking_synced_removes_from_the_queue_but_keeps_the_row(store):
    store.upsert_weigh_ins([make_weigh_in()])
    store.mark_synced(["id-1"])

    assert store.pending_count() == 0
    assert len(store.latest()) == 1  # massalarme never deletes what it uploaded


def test_dropped_readings_leave_the_queue_but_stay_local(store):
    store.upsert_weigh_ins([make_weigh_in()])
    store.mark_dropped("id-1", "value out of range")

    assert store.pending_count() == 0
    assert store.status()["dropped"] == 1
    assert len(store.latest()) == 1


def test_backfill_over_the_raw_log_is_idempotent(store):
    for timestamp, weight, raw in BURST:
        store.record_raw(
            Measurement(
                captured_at=parse_timestamp(timestamp), weight_kg=weight, raw_value=raw
            )
        )

    assert store.backfill_from_raw_log() == (1, 0)
    assert store.backfill_from_raw_log() == (0, 1)
    assert store.pending_count() == 1


def test_backfill_skips_unparseable_timestamps_without_dying(store):
    import sqlite3

    with sqlite3.connect(store.db_path) as conn:
        conn.execute(
            "INSERT INTO weights (timestamp, weight_kg, impedance, raw_value) "
            "VALUES ('not-a-date', 70.0, -1, 'abc')"
        )

    inserted, _ = store.backfill_from_raw_log()
    assert inserted == 0


@pytest.mark.parametrize("sentinel", [-1.0, 65533.0])
def test_impedance_sentinels_become_null(store, sentinel):
    store.record_raw(
        Measurement(
            captured_at=datetime(2026, 8, 9, 7, 0, 0),
            weight_kg=73.9,
            impedance=sentinel,
            raw_value="abc",
        )
    )
    store.backfill_from_raw_log()

    assert store.latest()[0].impedance is None
