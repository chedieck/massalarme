"""ontoplano planner occurrences -> massalarme alarms.

The mapping rules live here on purpose: ontoplano reports what is scheduled and
knows nothing about alarms, scales or ringtones.
"""

from datetime import datetime, timedelta

import pytest

import schedule_sync
from schedule_sync import (
    ATTR_HARD,
    ATTR_RING,
    ATTR_SOFT,
    ORIGIN_ONTOPLANO,
    build_alarms,
    classify,
    merge_into_schedule,
    occurrence_to_alarm,
)

NOW_MS = int(datetime(2026, 8, 9, 12, 0, 0).timestamp() * 1000)


def occurrence(**overrides) -> dict:
    base = {
        "id": "slot:1423",
        "source": "slot",
        "at_local": "2026-08-11T07:00:00",
        "local_date": "2026-08-11",
        "start_time": "07:00",
        "duration_minutes": 30,
        "title": "Wake up",
        "category": "duty",
        "label": "",
        "status": "pending",
        # Marked hard by default, so a test about grouping or merging says
        # nothing about how the kind was chosen.
        "attributes": {ATTR_HARD: "true"},
    }
    base.update(overrides)
    return base


# ── Classification ───────────────────────────────────────────────────


def test_the_hard_attribute_asks_for_the_siren_and_the_scale():
    assert classify(occurrence(attributes={ATTR_HARD: "true"})) == "hard"


def test_the_short_attribute_rings_soft():
    assert classify(occurrence(attributes={ATTR_RING: "true"})) == "soft"


def test_the_spelt_out_soft_attribute_is_the_same_thing():
    assert classify(occurrence(attributes={ATTR_SOFT: "true"})) == "soft"


def test_hard_wins_when_a_block_carries_both():
    # Guessing the other way lets a block that is meant to need the scale be
    # dismissed with a tap, which is the failure that matters.
    assert classify(occurrence(attributes={ATTR_SOFT: "true", ATTR_HARD: "true"})) == "hard"


def test_a_block_with_no_attributes_gets_no_alarm():
    assert classify(occurrence(attributes={})) is None
    assert classify({"id": "slot:1", "at_local": "2026-08-11T07:00:00"}) is None


def test_the_title_is_no_longer_consulted():
    # The whole point of the move: renaming a block must not silence it, and
    # naming an unrelated one "wake up" must not make it ring.
    assert classify(occurrence(title="Levantar", attributes={ATTR_RING: "true"})) == "soft"
    assert classify(occurrence(title="Wake up", attributes={})) is None


@pytest.mark.parametrize("value", ["true", "TRUE", " yes ", "1", "y", "on"])
def test_only_an_explicit_yes_rings(value):
    assert classify(occurrence(attributes={ATTR_RING: value})) == "soft"


@pytest.mark.parametrize("value", ["false", "0", "no", "", "maybe", "ture"])
def test_anything_else_is_a_no(value):
    # Including the values that look like a mistake: an alarm clock that rings
    # at 05:00 because of a typo is worse than one that stays quiet.
    assert classify(occurrence(attributes={ATTR_RING: value})) is None


def test_attributes_are_also_read_under_their_old_name():
    # An older ontoplano answers the same object as `meta`.
    occ = occurrence(attributes={})
    del occ["attributes"]
    occ["meta"] = {ATTR_HARD: "true"}
    assert classify(occ) == "hard"


# ── Alarm construction ───────────────────────────────────────────────


def test_at_local_is_used_as_wall_clock_without_timezone_conversion():
    """The brief is explicit: schedule against at_local read in the user's
    timezone, not against a UTC conversion invented on this side."""
    alarm = occurrence_to_alarm(occurrence(), "hard", NOW_MS)

    assert alarm["time"] == "07:00"
    assert alarm["date"] == "11-08-2026"


def test_the_alarm_id_is_stable_across_syncs():
    first = occurrence_to_alarm(occurrence(), "hard", NOW_MS)
    second = occurrence_to_alarm(occurrence(), "hard", NOW_MS + 900_000)

    assert first["id"] == second["id"]
    assert first["origin"] == ORIGIN_ONTOPLANO
    assert first["origin_id"] == "slot:1423"


def test_occurrences_missing_required_fields_are_skipped():
    assert occurrence_to_alarm(occurrence(at_local=None), "hard", NOW_MS) is None
    assert occurrence_to_alarm(occurrence(id=""), "hard", NOW_MS) is None
    assert occurrence_to_alarm(occurrence(at_local="not-a-date"), "hard", NOW_MS) is None


def test_build_alarms_over_a_whole_response():
    schedule = {
        "timezone": "America/Sao_Paulo",
        "occurrences": [
            occurrence(id="slot:1", title="Wake up"),
            occurrence(id="slot:2", title="Dentist", attributes={ATTR_RING: "true"}),
            occurrence(id="slot:3", title="Nap", attributes={}),
        ],
    }

    alarms = build_alarms(schedule, NOW_MS)

    assert [a["kind"] for a in alarms] == ["hard", "soft"]
    assert len(alarms) == 2


def test_an_empty_schedule_is_not_an_error():
    assert build_alarms({"occurrences": []}, NOW_MS) == []
    assert build_alarms({}, NOW_MS) == []


# ── Merging ──────────────────────────────────────────────────────────


def test_hand_made_alarms_are_never_touched():
    current = {
        "version": 2,
        "alarms": [{"id": "manual-1", "name": "My alarm", "time": "06:00", "enabled": True}],
    }
    derived = build_alarms({"occurrences": [occurrence()]}, NOW_MS)

    merged = merge_into_schedule(current, derived, now_ms=NOW_MS, horizon_days=7)

    manual = [a for a in merged["alarms"] if a["id"] == "manual-1"]
    assert manual == current["alarms"]
    assert len(merged["alarms"]) == 2


def test_resyncing_updates_in_place_instead_of_duplicating():
    derived = build_alarms({"occurrences": [occurrence()]}, NOW_MS)
    once = merge_into_schedule({"version": 2, "alarms": []}, derived, now_ms=NOW_MS, horizon_days=7)
    twice = merge_into_schedule(once, derived, now_ms=NOW_MS, horizon_days=7)

    assert len(twice["alarms"]) == 1


def test_an_unchanged_occurrence_does_not_bump_updated_at():
    """Otherwise a sync every few minutes would keep beating a genuine edit made
    on the phone, whose merge resolves by latest updated_at."""
    derived = build_alarms({"occurrences": [occurrence()]}, NOW_MS)
    once = merge_into_schedule({"version": 2, "alarms": []}, derived, now_ms=NOW_MS, horizon_days=7)

    later = build_alarms({"occurrences": [occurrence()]}, NOW_MS + 3_600_000)
    twice = merge_into_schedule(once, later, now_ms=NOW_MS + 3_600_000, horizon_days=7)

    assert twice["alarms"][0]["updated_at"] == NOW_MS


def test_a_changed_time_does_bump_updated_at():
    derived = build_alarms({"occurrences": [occurrence()]}, NOW_MS)
    once = merge_into_schedule({"version": 2, "alarms": []}, derived, now_ms=NOW_MS, horizon_days=7)

    moved = build_alarms(
        {"occurrences": [occurrence(at_local="2026-08-11T08:30:00")]},
        NOW_MS + 3_600_000,
    )
    twice = merge_into_schedule(once, moved, now_ms=NOW_MS + 3_600_000, horizon_days=7)

    assert twice["alarms"][0]["time"] == "08:30"
    assert twice["alarms"][0]["updated_at"] == NOW_MS + 3_600_000


def test_an_occurrence_deleted_upstream_is_tombstoned():
    derived = build_alarms({"occurrences": [occurrence()]}, NOW_MS)
    once = merge_into_schedule({"version": 2, "alarms": []}, derived, now_ms=NOW_MS, horizon_days=7)

    emptied = merge_into_schedule(once, [], now_ms=NOW_MS, horizon_days=7)

    assert emptied["alarms"][0]["deleted"] is True


def test_alarms_beyond_the_fetched_window_are_not_deleted():
    """We asked for 7 days; an alarm derived for next month is absent from the
    response for that reason alone and must survive."""
    far_future = (datetime.fromtimestamp(NOW_MS / 1000) + timedelta(days=45)).strftime(
        "%d-%m-%Y"
    )
    current = {
        "version": 2,
        "alarms": [
            {
                "id": "op-slot-99",
                "name": "Far away",
                "time": "07:00",
                "date": far_future,
                "enabled": True,
                "origin": ORIGIN_ONTOPLANO,
                "origin_id": "slot:99",
                "updated_at": NOW_MS,
            }
        ],
    }

    merged = merge_into_schedule(current, [], now_ms=NOW_MS, horizon_days=7)

    assert merged["alarms"][0].get("deleted") is not True


# ── Pairing payload ──────────────────────────────────────────────────
#
# The QR is read off a terminal by a phone camera, so density decides whether
# the app can be set up at all. These guard the property that broke it.

import qrcode  # noqa: E402

from alarm_manager import (  # noqa: E402
    PROVISIONING_PREFIX,
    build_provisioning_payload,
    encode_provisioning,
)

SECRET = "0d0f6d6f3a893d52d1275da38087d5f3d359e2d36800c0eaf502edbdc3fff710"

CFG = {
    "shared_secret": SECRET,
    "pc_port": 8888,
    "lan_network": "192.168.1.0/24",
    "syncing_weight_flag": 164,
    "min_weight_kg": 68,
    "weigh_in_gap_seconds": 90,
}


def qr_modules(data: str) -> int:
    code = qrcode.QRCode(border=0)
    code.add_data(data)
    code.make(fit=True)
    return len(code.get_matrix())


def test_the_pairing_payload_stays_sparse_enough_to_scan():
    """A JSON payload needed a 63x63 code, which no phone could read off a
    terminal. Anything much past the original 39x39 is a regression."""
    encoded = encode_provisioning(build_provisioning_payload(CFG))

    assert qr_modules(encoded) <= 41


def test_every_character_stays_in_qr_alphanumeric_mode():
    """That mode is what buys the density back. A lowercase letter silently
    forces byte mode and inflates the code."""
    allowed = set("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:")
    encoded = encode_provisioning(build_provisioning_payload(CFG))

    assert set(encoded) <= allowed


def test_the_payload_carries_what_the_phone_needs():
    encoded = encode_provisioning(build_provisioning_payload(CFG))
    parts = encoded.split(":")

    assert parts[0] == PROVISIONING_PREFIX
    assert parts[1] == SECRET.upper()
    assert parts[3] == "8888"
    assert parts[4] == "164"
    assert parts[5] == "68"       # integral weights lose the ".0"
    assert parts[6] == "90"


def test_a_fractional_minimum_weight_survives():
    payload = build_provisioning_payload({**CFG, "min_weight_kg": 67.5})
    assert encode_provisioning(payload).split(":")[5] == "67.5"


def test_the_encoded_secret_is_the_configured_one_case_aside():
    encoded = encode_provisioning(build_provisioning_payload(CFG))
    assert encoded.split(":")[1].lower() == SECRET


# ── Repeated activities ──────────────────────────────────────────────
#
# ontoplano's grid editor duplicates an activity across days, which arrives as
# one occurrence per day. Emitting a dated alarm for each turned one habit into
# several unrelated single-day alarms.


def gym(slot: int, day: int, time: str = "07:00", title: str = "Gym") -> dict:
    return occurrence(
        id=f"slot:{slot}", title=title, at_local=f"2026-08-{day:02d}T{time}:00"
    )



def test_an_activity_repeated_across_days_becomes_one_weekly_alarm():
    # 10, 12 and 14 August 2026 are Monday, Wednesday and Friday.
    schedule = {"occurrences": [gym(1, 10), gym(2, 12), gym(3, 14)]}

    alarms = build_alarms(schedule, NOW_MS)

    assert len(alarms) == 1
    assert alarms[0]["days"] == ["monday", "wednesday", "friday"]
    assert alarms[0]["time"] == "07:00"
    assert "date" not in alarms[0]


def test_a_one_off_activity_keeps_its_date():
    alarms = build_alarms({"occurrences": [gym(9, 11)]}, NOW_MS)

    assert len(alarms) == 1
    assert alarms[0]["date"] == "11-08-2026"
    assert "days" not in alarms[0]


def test_the_same_activity_at_different_times_stays_separate():
    """07:00 gym and 19:00 gym are two different habits."""
    schedule = {
        "occurrences": [gym(1, 10), gym(2, 12), gym(3, 10, "19:00"), gym(4, 12, "19:00")]
    }

    alarms = build_alarms(schedule, NOW_MS)

    assert sorted(a["time"] for a in alarms) == ["07:00", "19:00"]
    assert all(a["days"] == ["monday", "wednesday"] for a in alarms)


def test_different_activities_at_the_same_time_stay_separate():
    schedule = {
        "occurrences": [
            gym(1, 10), gym(2, 12),
            gym(3, 10, title="Swim"), gym(4, 12, title="Swim"),
        ]
    }

    alarms = build_alarms(schedule, NOW_MS)

    assert sorted(a["name"] for a in alarms) == ["Gym", "Swim"]


def test_the_weekly_id_is_stable_across_syncs():
    """Otherwise every sync would tombstone the alarm and add a new one."""
    schedule = {"occurrences": [gym(1, 10), gym(2, 12)]}

    first = build_alarms(schedule, NOW_MS)
    # Same activity next week: different occurrence ids, same habit.
    later = build_alarms(
        {"occurrences": [gym(77, 17), gym(78, 19)]}, NOW_MS + 604_800_000
    )

    assert first[0]["id"] == later[0]["id"]


def test_a_repeated_activity_resyncs_without_duplicating():
    schedule = {"occurrences": [gym(1, 10), gym(2, 12)]}
    derived = build_alarms(schedule, NOW_MS)

    once = merge_into_schedule({"version": 2, "alarms": []}, derived, now_ms=NOW_MS, horizon_days=7)
    twice = merge_into_schedule(once, derived, now_ms=NOW_MS, horizon_days=7)

    assert len([a for a in twice["alarms"] if not a.get("deleted")]) == 1
