"""ontoplano planner occurrences -> massalarme alarms.

The mapping rules live here on purpose: ontoplano reports what is scheduled and
knows nothing about alarms, scales or ringtones.
"""

from datetime import datetime, timedelta

import pytest

import schedule_sync
from schedule_sync import (
    ORIGIN_ONTOPLANO,
    build_alarms,
    classify,
    merge_into_schedule,
    occurrence_to_alarm,
    parse_rules,
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
    }
    base.update(overrides)
    return base


RULES = parse_rules(
    [
        {"match": {"title": "(?i)wake up"}, "kind": "hard"},
        {"match": {"category": "duty"}, "kind": "soft"},
    ]
)


# ── Classification ───────────────────────────────────────────────────


def test_a_wake_up_task_becomes_a_hard_alarm():
    assert classify(occurrence(), RULES) == "hard"


def test_first_matching_rule_wins():
    # "Wake up" is also category=duty, but the hard rule comes first.
    assert classify(occurrence(title="Wake up"), RULES) == "hard"
    assert classify(occurrence(title="Dentist"), RULES) == "soft"


def test_an_occurrence_matching_nothing_gets_no_alarm():
    assert classify(occurrence(title="Read a book", category="leisure"), RULES) is None


def test_title_matching_is_case_insensitive_when_the_regex_says_so():
    assert classify(occurrence(title="WAKE UP EARLY"), RULES) == "hard"


def test_a_bad_regex_is_skipped_rather_than_crashing():
    rules = parse_rules([{"match": {"title": "([unclosed"}, "kind": "hard"}])
    assert rules == []


def test_an_unknown_kind_is_skipped():
    assert parse_rules([{"match": {"category": "duty"}, "kind": "nuclear"}]) == []


def test_label_matching():
    rules = parse_rules([{"match": {"label": "gym"}, "kind": "soft"}])
    assert classify(occurrence(label="gym"), rules) == "soft"
    assert classify(occurrence(label="other"), rules) is None


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
            occurrence(id="slot:2", title="Dentist", category="duty"),
            occurrence(id="slot:3", title="Nap", category="leisure"),
        ],
    }

    alarms = build_alarms(schedule, RULES, NOW_MS)

    assert [a["kind"] for a in alarms] == ["hard", "soft"]
    assert len(alarms) == 2


def test_an_empty_schedule_is_not_an_error():
    assert build_alarms({"occurrences": []}, RULES, NOW_MS) == []
    assert build_alarms({}, RULES, NOW_MS) == []


# ── Merging ──────────────────────────────────────────────────────────


def test_hand_made_alarms_are_never_touched():
    current = {
        "version": 2,
        "alarms": [{"id": "manual-1", "name": "My alarm", "time": "06:00", "enabled": True}],
    }
    derived = build_alarms({"occurrences": [occurrence()]}, RULES, NOW_MS)

    merged = merge_into_schedule(current, derived, now_ms=NOW_MS, horizon_days=7)

    manual = [a for a in merged["alarms"] if a["id"] == "manual-1"]
    assert manual == current["alarms"]
    assert len(merged["alarms"]) == 2


def test_resyncing_updates_in_place_instead_of_duplicating():
    derived = build_alarms({"occurrences": [occurrence()]}, RULES, NOW_MS)
    once = merge_into_schedule({"version": 2, "alarms": []}, derived, now_ms=NOW_MS, horizon_days=7)
    twice = merge_into_schedule(once, derived, now_ms=NOW_MS, horizon_days=7)

    assert len(twice["alarms"]) == 1


def test_an_unchanged_occurrence_does_not_bump_updated_at():
    """Otherwise a sync every few minutes would keep beating a genuine edit made
    on the phone, whose merge resolves by latest updated_at."""
    derived = build_alarms({"occurrences": [occurrence()]}, RULES, NOW_MS)
    once = merge_into_schedule({"version": 2, "alarms": []}, derived, now_ms=NOW_MS, horizon_days=7)

    later = build_alarms({"occurrences": [occurrence()]}, RULES, NOW_MS + 3_600_000)
    twice = merge_into_schedule(once, later, now_ms=NOW_MS + 3_600_000, horizon_days=7)

    assert twice["alarms"][0]["updated_at"] == NOW_MS


def test_a_changed_time_does_bump_updated_at():
    derived = build_alarms({"occurrences": [occurrence()]}, RULES, NOW_MS)
    once = merge_into_schedule({"version": 2, "alarms": []}, derived, now_ms=NOW_MS, horizon_days=7)

    moved = build_alarms(
        {"occurrences": [occurrence(at_local="2026-08-11T08:30:00")]},
        RULES,
        NOW_MS + 3_600_000,
    )
    twice = merge_into_schedule(once, moved, now_ms=NOW_MS + 3_600_000, horizon_days=7)

    assert twice["alarms"][0]["time"] == "08:30"
    assert twice["alarms"][0]["updated_at"] == NOW_MS + 3_600_000


def test_an_occurrence_deleted_upstream_is_tombstoned():
    derived = build_alarms({"occurrences": [occurrence()]}, RULES, NOW_MS)
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
