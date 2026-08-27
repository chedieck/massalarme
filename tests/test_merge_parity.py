"""The phone and the PC must merge schedules identically.

Both sides hold a copy of the schedule and both may edit it offline, so the
merge rule is the thing that decides whether an edit survives. If the two
implementations disagree the schedule ping-pongs: each side "corrects" the
other on every sync and the user's alarm quietly reverts.

These cases are the same ones asserted in Kotlin in
`lanalarm/app/src/test/kotlin/org/example/MergeTest.kt`. Changing one without
the other is the bug this file exists to catch.
"""

from alarm_manager import merge_alarms, _prune_old_tombstones

DAY_MS = 86_400 * 1000


def alarm(aid, time, updated_at, *, enabled=True, deleted=False):
    entry = {
        "id": aid,
        "name": "Weigh-in",
        "time": time,
        "type": "next",
        "enabled": enabled,
        "kind": "hard",
        "updated_at": updated_at,
    }
    if deleted:
        entry["deleted"] = True
    return entry


def schedule(*alarms):
    return {"version": 2, "alarms": list(alarms)}


def times_by_id(config):
    return {a["id"]: a["time"] for a in config["alarms"]}


def test_a_newer_remote_edit_wins():
    merged = merge_alarms(
        schedule(alarm("a", "07:00", 100)),
        schedule(alarm("a", "09:00", 200)),
    )
    assert times_by_id(merged) == {"a": "09:00"}


def test_a_newer_local_edit_survives_a_stale_remote_copy():
    merged = merge_alarms(
        schedule(alarm("a", "09:00", 200)),
        schedule(alarm("a", "07:00", 100)),
    )
    assert times_by_id(merged) == {"a": "09:00"}


def test_a_tie_keeps_the_local_copy():
    """The incumbent wins, matching AlarmSchedule.merge on the phone."""
    merged = merge_alarms(
        schedule(alarm("a", "09:00", 100)),
        schedule(alarm("a", "07:00", 100)),
    )
    assert times_by_id(merged) == {"a": "09:00"}


def test_an_alarm_created_offline_on_either_side_is_kept():
    merged = merge_alarms(
        schedule(alarm("phone", "07:00", 100)),
        schedule(alarm("pc", "08:00", 100)),
    )
    assert times_by_id(merged) == {"phone": "07:00", "pc": "08:00"}


def test_a_deletion_propagates_instead_of_being_resurrected():
    merged = merge_alarms(
        schedule(alarm("a", "07:00", 100)),
        schedule(alarm("a", "07:00", 200, enabled=False, deleted=True)),
    )
    assert len(merged["alarms"]) == 1
    assert merged["alarms"][0]["deleted"] is True


def test_merging_is_stable_across_a_sync_round_trip():
    local = schedule(alarm("a", "07:00", 100), alarm("b", "08:00", 200))
    remote = schedule(alarm("a", "09:00", 300))

    once = merge_alarms(local, remote)
    twice = merge_alarms(once, remote)
    assert times_by_id(once) == times_by_id(twice)


def test_a_fresh_tombstone_is_kept_but_an_ancient_one_is_pruned(monkeypatch):
    now_ms = 40 * DAY_MS
    monkeypatch.setattr("alarm_manager._now_ms", lambda: now_ms)

    fresh = _prune_old_tombstones(
        schedule(alarm("a", "07:00", now_ms - 1000, deleted=True))
    )
    assert len(fresh["alarms"]) == 1

    ancient = _prune_old_tombstones(schedule(alarm("a", "07:00", 0, deleted=True)))
    assert ancient["alarms"] == []
