"""Turn ontoplano planner occurrences into massalarme alarms.

"Mark the block you want to wake up for, and that is when the alarm rings."

ontoplano reports *what is scheduled* and nothing else -- it knows nothing about
alarms, ringtones, scales or wifi, and it should stay that way. Deciding which
occurrences deserve an alarm, and whether that alarm is hard or soft, is
massalarme's business, and it is read off the block's **attributes** -- the
user-defined key/value pairs ontoplano stores on a task and never interprets:

    massalarme      = true   ring gently, one tap dismisses it
    soft_massalarme = true   the same thing, spelt out
    hard_massalarme = true   ring the siren until the scale reports a weight

Hard wins if a block carries both. There is nothing to configure: marking the
block that should wake you is a property of that block.

This replaced a regex matched against the title, which meant the alarm depended
on spelling -- renaming "Acordar" to "Levantar" silently stopped it happening.
The same vocabulary is what the phone reads, in `OntoplanoSchedule.kt`; the two
must agree, or one task becomes two different alarms depending on which side
last synced.

Alarms derived this way are dated one-shots carrying `origin: ontoplano` and the
occurrence's stable `id`, so a re-sync updates them in place instead of piling up
duplicates, and hand-made alarms are never touched.
"""

from __future__ import annotations

import hashlib
import logging
from datetime import datetime
from typing import Dict, List, Optional, Tuple

logger = logging.getLogger("massalarme.schedule")

ORIGIN_ONTOPLANO = "ontoplano"

KIND_HARD = "hard"
KIND_SOFT = "soft"


#: The source name, which is also the attribute namespace.
SOURCE = "massalarme"

#: Rings gently, dismissed with one tap.
ATTR_SOFT = f"soft_{SOURCE}"

#: Rings the siren and wants the scale. Beats ATTR_SOFT when both are set.
ATTR_HARD = f"hard_{SOURCE}"

#: The short spelling of ATTR_SOFT, and the one to reach for.
ATTR_RING = SOURCE

#: What counts as yes. Closed rather than "anything that is not false": an alarm
#: clock that rings at 05:00 because a value was misspelt is worse than one that
#: stays quiet and can be looked at over breakfast.
TRUE_VALUES = frozenset({"true", "1", "yes", "y", "on"})


def _attributes(occurrence: dict) -> dict:
    """The block's attributes, under either name.

    ontoplano answers the same object twice -- as `attributes`, and as `meta`
    for plugins written before the rename -- so reading both works against an
    older instance without a second code path.
    """
    for key in ("attributes", "meta"):
        value = occurrence.get(key)
        if isinstance(value, dict):
            return value
    return {}


def _is_true(attributes: dict, key: str) -> bool:
    return str(attributes.get(key, "")).strip().lower() in TRUE_VALUES


def classify(occurrence: dict) -> Optional[str]:
    """The alarm kind for this occurrence, or None if it deserves no alarm.

    Hard wins: somebody who has said both things about one block has asked for
    the stricter of the two, and guessing the other way lets a block that is
    supposed to need the scale be dismissed with a tap.
    """
    attributes = _attributes(occurrence)
    if _is_true(attributes, ATTR_HARD):
        return KIND_HARD
    if _is_true(attributes, ATTR_RING) or _is_true(attributes, ATTR_SOFT):
        return KIND_SOFT
    return None


def _alarm_id_for(occurrence_id: str) -> str:
    """Stable per occurrence, so a re-sync updates rather than duplicates."""
    return f"op-{occurrence_id.replace(':', '-')}"


def occurrence_to_alarm(occurrence: dict, kind: str, now_ms: int) -> Optional[dict]:
    """Build a v2 alarm from one occurrence.

    `at_local` is naive wall-clock in the user's timezone. It is used as-is: the
    phone and the PC both live in that timezone, and inventing a UTC conversion
    here is exactly what the ontoplano brief warns against.
    """
    at_local = occurrence.get("at_local")
    occurrence_id = str(occurrence.get("id", "")).strip()
    if not at_local or not occurrence_id:
        return None

    try:
        moment = datetime.fromisoformat(str(at_local))
    except ValueError:
        logger.warning("Skipping occurrence %s: unparseable at_local %r", occurrence_id, at_local)
        return None

    return {
        "id": _alarm_id_for(occurrence_id),
        "name": str(occurrence.get("title") or "ontoplano"),
        "time": moment.strftime("%H:%M"),
        "date": moment.strftime("%d-%m-%Y"),
        "enabled": True,
        "kind": kind,
        "origin": ORIGIN_ONTOPLANO,
        "origin_id": occurrence_id,
        "updated_at": now_ms,
    }


WEEKDAYS = [
    "monday",
    "tuesday",
    "wednesday",
    "thursday",
    "friday",
    "saturday",
    "sunday",
]


def _weekly_alarm_id(title: str, time_text: str) -> str:
    digest = hashlib.sha1(f"{title.lower()}|{time_text}".encode("utf-8")).hexdigest()[:8]
    return f"op-w-{digest}"


def build_alarms(schedule: dict, now_ms: int) -> List[dict]:
    """Map a whole `/schedule/upcoming` response into alarms.

    The same activity repeated across several days -- which is what ontoplano's
    grid editor produces -- arrives as one occurrence per day. Emitting a dated
    alarm for each turns "gym at 07:00 on Mon/Wed/Fri" into three separate
    single-day alarms, which is not what the user set up and is miserable to
    manage.

    So occurrences sharing a title, a time and a kind are collapsed into one
    weekly alarm carrying all their weekdays. A title that genuinely happens once
    in the window stays a dated alarm.
    """
    matched: List[Tuple[dict, str, datetime]] = []
    for occurrence in schedule.get("occurrences", []) or []:
        if not isinstance(occurrence, dict):
            continue
        kind = classify(occurrence)
        if kind is None:
            continue
        at_local = occurrence.get("at_local")
        if not at_local:
            continue
        try:
            moment = datetime.fromisoformat(str(at_local))
        except ValueError:
            logger.warning("Skipping occurrence with unparseable at_local %r", at_local)
            continue
        matched.append((occurrence, kind, moment))

    # Group by what makes two occurrences "the same activity".
    groups: Dict[Tuple[str, str, str], List[Tuple[dict, str, datetime]]] = {}
    for occurrence, kind, moment in matched:
        key = (
            str(occurrence.get("title") or "ontoplano").lower(),
            moment.strftime("%H:%M"),
            kind,
        )
        groups.setdefault(key, []).append((occurrence, kind, moment))

    alarms: List[dict] = []
    for (_, time_text, kind), members in groups.items():
        weekdays = sorted(
            {WEEKDAYS[m[2].weekday()] for m in members},
            key=WEEKDAYS.index,
        )
        title = str(members[0][0].get("title") or "ontoplano")

        if len(weekdays) < 2:
            # A one-off keeps its exact date; a weekly alarm would fire again
            # next week for something that only happens once.
            alarm = occurrence_to_alarm(members[0][0], kind, now_ms)
            if alarm:
                alarms.append(alarm)
            continue

        alarms.append(
            {
                "id": _weekly_alarm_id(title, time_text),
                "name": title,
                "time": time_text,
                "days": weekdays,
                "enabled": True,
                "kind": kind,
                "origin": ORIGIN_ONTOPLANO,
                # Every occurrence this alarm stands for, so a later sync can
                # tell whether the set has changed.
                "origin_id": ",".join(sorted(str(m[0].get("id", "")) for m in members)),
                "updated_at": now_ms,
            }
        )

    return alarms


def merge_into_schedule(
    current: Dict, derived: List[dict], *, now_ms: int, horizon_days: int
) -> Dict:
    """Fold ontoplano-derived alarms into the local schedule.

    Rules:

    * Hand-made alarms are never touched. Only entries carrying
      `origin: ontoplano` are managed here.
    * A derived alarm that still exists upstream is updated in place, keeping its
      id, so the phone's merge sees an edit rather than a delete plus an insert.
    * A previously derived alarm that has vanished upstream is tombstoned, but
      only inside the window we actually asked about -- an alarm derived for next
      month must not be deleted because we only fetched seven days.
    """
    existing = list(current.get("alarms", []) or [])
    derived_by_id = {alarm["id"]: alarm for alarm in derived}

    merged: List[dict] = []
    seen: set[str] = set()

    for alarm in existing:
        alarm_id = alarm.get("id")
        if alarm.get("origin") != ORIGIN_ONTOPLANO:
            merged.append(alarm)
            continue

        replacement = derived_by_id.get(alarm_id)
        if replacement is not None:
            # Only bump updated_at when something actually changed, so a sync
            # every few minutes does not keep winning merge conflicts against
            # a genuine edit made on the phone.
            comparable = {k: v for k, v in replacement.items() if k != "updated_at"}
            previous = {k: v for k, v in alarm.items() if k != "updated_at"}
            if comparable == previous:
                merged.append(alarm)
            else:
                merged.append(replacement)
            seen.add(alarm_id)
            continue

        if _within_horizon(alarm, now_ms, horizon_days):
            if not alarm.get("deleted"):
                logger.info("ontoplano occurrence gone -- tombstoning alarm %s", alarm_id)
            merged.append({**alarm, "deleted": True, "updated_at": now_ms})
        else:
            merged.append(alarm)

    for alarm_id, alarm in derived_by_id.items():
        if alarm_id not in seen:
            logger.info("New alarm from ontoplano: %s at %s", alarm["name"], alarm["time"])
            merged.append(alarm)

    return {"version": 2, "alarms": merged}


def _within_horizon(alarm: dict, now_ms: int, horizon_days: int) -> bool:
    """Is this dated alarm inside the range we just asked ontoplano about?"""
    date = alarm.get("date")
    if not date:
        return True
    try:
        moment = datetime.strptime(date, "%d-%m-%Y")
    except ValueError:
        return True
    delta_days = (moment - datetime.fromtimestamp(now_ms / 1000)).days
    return -1 <= delta_days <= horizon_days
