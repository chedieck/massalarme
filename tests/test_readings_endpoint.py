"""The phone -> PC hop: POST /readings.

Mirrors the ontoplano contract deliberately, so both hops share retry semantics
and a duplicate is success everywhere.
"""

from datetime import datetime, timezone

import pytest
import pytest_asyncio
from aiohttp import web

import alarm_manager
from store import WeighInStore, make_external_id

SECRET = "0d0f6d6f3a893d52d1275da38087d5f3"


@pytest.fixture(autouse=True)
def daemon_state(tmp_path, monkeypatch):
    """Point the module's globals at a throwaway database."""
    store = WeighInStore(tmp_path / "weights.db")
    store.init()

    monkeypatch.setattr(alarm_manager, "_store", store)
    monkeypatch.setattr(alarm_manager, "_sync_worker", None)
    monkeypatch.setattr(alarm_manager, "_current_cfg", {"shared_secret": SECRET})
    monkeypatch.setattr(alarm_manager, "_notify_send", lambda *a, **k: None)

    async def no_broadcast(_weight):
        return None

    monkeypatch.setattr(alarm_manager, "_broadcast_weight", no_broadcast)
    return store


@pytest_asyncio.fixture
async def api(aiohttp_client_factory, daemon_state):
    app = web.Application()
    app.router.add_post("/readings", alarm_manager._handle_readings)
    app.router.add_get("/sync-status", alarm_manager._handle_sync_status)
    return await aiohttp_client_factory(app)


@pytest_asyncio.fixture
async def aiohttp_client_factory():
    from aiohttp.test_utils import TestClient, TestServer

    clients = []

    async def factory(app):
        client = TestClient(TestServer(app))
        await client.start_server()
        clients.append(client)
        return client

    yield factory
    for client in clients:
        await client.close()


def reading(index: int = 0, **overrides) -> dict:
    payload = {
        "captured_at": f"2026-08-0{index + 1}T07:12:03Z",
        "weight_kg": 73.9 + index,
        "impedance": 576.0,
        "raw_value": f"02a4b2070114161133fdffbc3{index}",
        "alarm_name": "Wake up",
    }
    payload.update(overrides)
    return payload


async def post(api, readings, key=SECRET):
    return await api.post("/readings", params={"key": key}, json={"readings": readings})


# ── Auth ─────────────────────────────────────────────────────────────


async def test_a_bad_key_is_rejected(api):
    response = await post(api, [reading()], key="wrong")
    assert response.status == 403


async def test_a_missing_key_is_rejected(api):
    response = await api.post("/readings", json={"readings": []})
    assert response.status == 403


# ── Happy path ───────────────────────────────────────────────────────


async def test_readings_are_stored_and_queued_for_ontoplano(api, daemon_state):
    response = await post(api, [reading(0), reading(1)])

    assert response.status == 200
    body = await response.json()
    assert body["accepted"] == 2
    assert body["duplicates"] == 0
    assert body["rejected"] == []
    assert daemon_state.pending_count() == 2


async def test_resending_the_same_reading_reports_duplicates_not_an_error(api, daemon_state):
    await post(api, [reading(0)])
    response = await post(api, [reading(0)])

    body = await response.json()
    assert response.status == 200
    assert body["accepted"] == 0
    assert body["duplicates"] == 1
    assert daemon_state.pending_count() == 1


async def test_the_phone_reported_id_is_recomputed_from_content(api, daemon_state):
    """A phone claiming a wrong id must not be able to mint a second point."""
    honest = reading(0)
    liar = dict(honest, external_id="totally-made-up")

    await post(api, [honest])
    await post(api, [liar])

    assert daemon_state.pending_count() == 1
    expected = make_external_id(
        datetime(2026, 8, 1, 7, 12, 3, tzinfo=timezone.utc),
        honest["raw_value"],
        honest["weight_kg"],
    )
    assert daemon_state.latest()[0].external_id == expected


async def test_readings_are_attributed_to_the_phone(api, daemon_state):
    await post(api, [reading(0)])
    assert daemon_state.latest()[0].source == "phone"


# ── Partial success ──────────────────────────────────────────────────


async def test_a_malformed_reading_is_rejected_while_the_rest_are_stored(api, daemon_state):
    response = await post(api, [reading(0), {"weight_kg": 70.0}, reading(1)])

    body = await response.json()
    assert body["accepted"] == 2
    assert len(body["rejected"]) == 1
    assert "captured_at" in body["rejected"][0]["reason"]
    assert daemon_state.pending_count() == 2


@pytest.mark.parametrize(
    "bad_weight", [0, -5, 900], ids=["zero", "negative", "implausible"]
)
async def test_implausible_weights_are_rejected(api, daemon_state, bad_weight):
    response = await post(api, [reading(0, weight_kg=bad_weight)])

    body = await response.json()
    assert body["accepted"] == 0
    assert len(body["rejected"]) == 1
    assert daemon_state.pending_count() == 0


async def test_impedance_sentinel_is_stored_as_null(api, daemon_state):
    await post(api, [reading(0, impedance=65533.0)])
    assert daemon_state.latest()[0].impedance is None


async def test_a_non_object_entry_does_not_kill_the_batch(api, daemon_state):
    response = await post(api, ["nonsense", reading(0)])

    body = await response.json()
    assert body["accepted"] == 1
    assert len(body["rejected"]) == 1


# ── Guard rails ──────────────────────────────────────────────────────


async def test_a_missing_readings_array_is_a_400(api):
    response = await api.post("/readings", params={"key": SECRET}, json={"nope": []})
    assert response.status == 400


async def test_an_oversized_batch_is_refused(api):
    response = await post(api, [reading(0)] * 501)
    assert response.status == 400


async def test_an_empty_batch_is_fine(api):
    response = await post(api, [])
    assert response.status == 200
    assert (await response.json())["accepted"] == 0


# ── Status surface ───────────────────────────────────────────────────


async def test_sync_status_reports_the_queue(api, daemon_state):
    await post(api, [reading(0), reading(1)])

    response = await api.get("/sync-status", params={"key": SECRET})
    body = await response.json()

    assert body["total"] == 2
    assert body["pending"] == 2
    assert body["ontoplano_enabled"] is False
    assert body["halted"] is False


async def test_sync_status_needs_the_key(api):
    response = await api.get("/sync-status", params={"key": "wrong"})
    assert response.status == 403


# ── ontoplano rule from the phone ────────────────────────────────────


def test_the_phone_can_set_the_task_matching_rule(monkeypatch, tmp_path):
    """The pattern is edited on the phone but enforced here, because this is the
    side holding the ontoplano token."""
    cfg = {"shared_secret": SECRET, "ontoplano": {"schedule": {}}}
    saved = {}
    monkeypatch.setattr(alarm_manager, "_current_cfg", cfg)
    monkeypatch.setattr(alarm_manager, "_save_config", lambda c: saved.update(c))

    alarm_manager._apply_ontoplano_rule("(?i)^acordar", "hard")

    rules = cfg["ontoplano"]["schedule"]["rules"]
    assert rules == [{"match": {"title": "(?i)^acordar"}, "kind": "hard"}]
    assert cfg["ontoplano"]["schedule"]["enabled"] is True
    assert saved, "the rule must be persisted, not just held in memory"


def test_an_empty_pattern_turns_schedule_sync_off(monkeypatch):
    cfg = {
        "shared_secret": SECRET,
        "ontoplano": {"schedule": {"enabled": True, "rules": [{"match": {"title": "x"}}]}},
    }
    monkeypatch.setattr(alarm_manager, "_current_cfg", cfg)
    monkeypatch.setattr(alarm_manager, "_save_config", lambda c: None)

    alarm_manager._apply_ontoplano_rule("", "hard")

    assert cfg["ontoplano"]["schedule"]["rules"] == []
    assert cfg["ontoplano"]["schedule"]["enabled"] is False


def test_an_invalid_pattern_is_refused_and_leaves_the_old_rule(monkeypatch):
    existing = [{"match": {"title": "good"}, "kind": "hard"}]
    cfg = {"shared_secret": SECRET, "ontoplano": {"schedule": {"rules": existing}}}
    monkeypatch.setattr(alarm_manager, "_current_cfg", cfg)
    monkeypatch.setattr(alarm_manager, "_save_config", lambda c: None)

    alarm_manager._apply_ontoplano_rule("([unclosed", "hard")

    assert cfg["ontoplano"]["schedule"]["rules"] == existing


def test_an_unknown_kind_falls_back_to_hard(monkeypatch):
    cfg = {"shared_secret": SECRET, "ontoplano": {"schedule": {}}}
    monkeypatch.setattr(alarm_manager, "_current_cfg", cfg)
    monkeypatch.setattr(alarm_manager, "_save_config", lambda c: None)

    alarm_manager._apply_ontoplano_rule("wake", "nuclear")

    assert cfg["ontoplano"]["schedule"]["rules"][0]["kind"] == "hard"


# ── First-run backfill ───────────────────────────────────────────────


def test_a_raw_log_that_was_never_collapsed_is_backfilled_on_startup(tmp_path, monkeypatch):
    """Otherwise the weigh-in history stays empty until someone happens to run
    `make backfill`, and the app's Weight tab just looks broken."""
    from store import Measurement, WeighInStore

    db_path = tmp_path / "weights.db"
    seed = WeighInStore(db_path)
    seed.init()
    for second in (0, 1, 2):
        seed.record_raw(
            Measurement(
                captured_at=datetime(2026, 8, 9, 7, 12, second),
                weight_kg=73.9,
                raw_value="02a4b2070114161133fdffbc39",
            )
        )
    assert seed.latest() == []          # raw rows only, no weigh-ins yet

    monkeypatch.setattr(alarm_manager, "DB_FILE", db_path)
    monkeypatch.setattr(alarm_manager, "_store", None)

    alarm_manager.init_db({})

    assert len(alarm_manager.get_store().latest()) == 1


def test_startup_backfill_does_not_run_when_weigh_ins_already_exist(tmp_path, monkeypatch):
    from store import WeighInStore

    db_path = tmp_path / "weights.db"
    store = WeighInStore(db_path)
    store.init()
    store.upsert_weigh_ins([make_weigh_in_for_backfill()])
    store.mark_synced(["already-here"])

    monkeypatch.setattr(alarm_manager, "DB_FILE", db_path)
    monkeypatch.setattr(alarm_manager, "_store", None)

    alarm_manager.init_db({})

    assert len(alarm_manager.get_store().latest()) == 1


def make_weigh_in_for_backfill():
    from store import WeighIn

    return WeighIn(
        external_id="already-here",
        captured_at=datetime(2026, 8, 9, 7, 0, 0, tzinfo=timezone.utc),
        weight_kg=70.0,
        impedance=None,
        raw_value=None,
        alarm_name=None,
        source="pc",
    )
