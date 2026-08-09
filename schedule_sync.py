"""Turn ontoplano planner occurrences into massalarme alarms.

"Put a task 'wake up' at 07:00 Tuesday and that is when the alarm rings."

ontoplano reports *what is scheduled* and nothing else -- it knows nothing about
alarms, ringtones, scales or wifi, and it should stay that way. Deciding which
occurrences deserve an alarm, and whether that alarm is hard or soft, is
massalarme's business and lives in `config.yaml`:

    ontoplano:
      schedule:
        enabled: true
        days: 7
        rules:
          - match: {title: "(?i)wake up"}
            kind: hard
          - match: {category: duty}
            kind: soft

First matching rule wins; occurrences that match nothing get no alarm.

Alarms derived this way are dated one-shots carrying `origin: ontoplano` and the
occurrence's stable `id`, so a re-sync updates them in place instead of piling up
duplicates, and hand-made alarms are never touched.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Dict, List, Optional

logger = logging.getLogger("massalarme.schedule")

ORIGIN_ONTOPLANO = "ontoplano"

KIND_HARD = "hard"
KIND_SOFT = "soft"


@dataclass
class Rule:
    """One mapping from an occurrence to an alarm kind."""

    kind: str
    title: Optional[re.Pattern] = None
    category: Optional[str] = None
    label: Optional[str] = None

    def matches(self, occurrence: dict) -> bool:
        if self.title is not None:
            if not self.title.search(str(occurrence.get("title", ""))):
                return False
        if self.category is not None:
            if str(occurrence.get("category", "")).lower() != self.category.lower():
                return False
        if self.label is not None:
            if str(occurrence.get("label", "")).lower() != self.label.lower():
                return False
        # A rule with no conditions would match everything; treat it as a
        # catch-all only if it was written that way deliberately.
        return True


def parse_rules(raw_rules: List[dict]) -> List[Rule]:
    rules: List[Rule] = []
    for entry in raw_rules or []:
        if not isinstance(entry, dict):
            continue
        match = entry.get("match") or {}
        kind = str(entry.get("kind", KIND_HARD)).lower()
        if kind not in (KIND_HARD, KIND_SOFT):
            logger.warning("Ignoring rule with unknown kind %r", kind)
            continue

        title_pattern = match.get("title")
        try:
            compiled = re.compile(title_pattern) if title_pattern else None
        except re.error as exc:
            logger.warning("Ignoring rule with bad title regex %r: %s", title_pattern, exc)
            continue

        rules.append(
            Rule(
                kind=kind,
                title=compiled,
                category=match.get("category"),
                label=match.get("label"),
            )
        )
    return rules


def classify(occurrence: dict, rules: List[Rule]) -> Optional[str]:
    """The alarm kind for this occurrence, or None if it deserves no alarm."""
    for rule in rules:
        if rule.matches(occurrence):
            return rule.kind
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


def build_alarms(schedule: dict, rules: List[Rule], now_ms: int) -> List[dict]:
    """Map a whole `/schedule/upcoming` response into alarms."""
    alarms: List[dict] = []
    for occurrence in schedule.get("occurrences", []) or []:
        if not isinstance(occurrence, dict):
            continue
        kind = classify(occurrence, rules)
        if kind is None:
            continue
        alarm = occurrence_to_alarm(occurrence, kind, now_ms)
        if alarm:
            alarms.append(alarm)
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
