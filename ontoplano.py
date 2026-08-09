"""ontoplano stream producer.

massalarme pushes weigh-ins to ontoplano over HTTP. massalarme remains the source
of truth; ontoplano holds a projection for charting. Nothing here ever deletes
local data, and nothing here is required for massalarme to work -- with sync
disabled the daemon behaves exactly as before.

The server endpoints this targets (`/api/v1/streams`) do not exist yet, so the
client is written against an interface with a null implementation and a
configuration flag. `tests/mock_ontoplano.py` implements the documented contract
so the whole path is exercisable today.
"""

from __future__ import annotations

import json
import logging
import os
import stat
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Protocol, Sequence

import aiohttp

logger = logging.getLogger("massalarme.ontoplano")

STREAM_SLUG = "massalarme.weight"

STREAM_DECLARATION = {
    "slug": STREAM_SLUG,
    "name": "Weight",
    "source": "massalarme",
    "kind": "measurement",
    "unit": "kg",
    "display": "line_chart",
}

MAX_POINTS_PER_REQUEST = 500


# =====================================================================
# Errors
# =====================================================================


class OntoplanoError(Exception):
    """Base class. `retryable` decides whether the queue holds or gives up."""

    retryable = True

    def __init__(self, message: str, *, status: Optional[int] = None):
        super().__init__(message)
        self.status = status


class AuthError(OntoplanoError):
    """401 -- bad or revoked token. Stop and tell the user to reconnect."""

    retryable = False


class PlanLimitError(OntoplanoError):
    """402 -- plan limit hit. Stop and surface the server's message."""

    retryable = False


class ForbiddenError(OntoplanoError):
    """403 -- the token authenticated but lacks the scope.

    Distinct from 401 on purpose: the fix is to reissue the token with
    `streams:write`, not to reconnect.
    """

    retryable = False


class StreamMissingError(OntoplanoError):
    """404 -- stream not declared. Re-declare once, then retry."""

    retryable = True


class RateLimitedError(OntoplanoError):
    """429 -- back off, honouring Retry-After."""

    def __init__(self, message: str, *, retry_after: Optional[float] = None):
        super().__init__(message, status=429)
        self.retry_after = retry_after


class ServerError(OntoplanoError):
    """5xx or a transport failure -- keep queued and back off."""


# =====================================================================
# Payloads
# =====================================================================


@dataclass
class Point:
    external_id: str
    at: str  # UTC ISO-8601 with Z
    value: float
    meta: Optional[dict] = None

    def to_json(self) -> dict:
        payload = {"external_id": self.external_id, "at": self.at, "value": self.value}
        if self.meta:
            payload["meta"] = self.meta
        return payload


@dataclass
class PushResult:
    accepted: list[str]
    duplicates: list[str]
    rejected: list[tuple[str, str]]  # (external_id, reason)

    @property
    def delivered(self) -> list[str]:
        """Accepted and duplicate alike. A duplicate means the server already has
        it, which is the designed outcome of a retry -- not an error."""
        return self.accepted + self.duplicates


# =====================================================================
# Configuration
# =====================================================================

TOKEN_FILENAME = "ontoplano_token"


@dataclass
class OntoplanoConfig:
    enabled: bool = False
    base_url: str = ""
    token: str = ""
    timeout_seconds: float = 15.0
    batch_size: int = MAX_POINTS_PER_REQUEST

    @property
    def usable(self) -> bool:
        return bool(self.enabled and self.base_url and self.token)


def read_token(config_dir: Path) -> str:
    """Read the bearer token from its own 0600 file.

    Deliberately not in config.yaml: that file is world-readable by default and
    gets pasted into bug reports. Returns "" when absent.
    """
    token_path = config_dir / TOKEN_FILENAME
    if not token_path.exists():
        return ""

    mode = stat.S_IMODE(token_path.stat().st_mode)
    if mode & 0o077:
        logger.warning(
            "%s is mode %o -- tightening to 0600 (it holds a secret)", token_path, mode
        )
        token_path.chmod(0o600)

    return token_path.read_text(encoding="utf-8").strip()


def write_token(config_dir: Path, token: str) -> Path:
    config_dir.mkdir(parents=True, exist_ok=True)
    token_path = config_dir / TOKEN_FILENAME
    # Create with the right mode from the start; never widen an existing file.
    handle = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(handle, "w", encoding="utf-8") as fh:
        fh.write(token.strip() + "\n")
    token_path.chmod(0o600)
    return token_path


def config_from_dict(cfg: dict, config_dir: Path) -> OntoplanoConfig:
    section = cfg.get("ontoplano") or {}
    return OntoplanoConfig(
        enabled=bool(section.get("enabled", False)),
        base_url=str(section.get("base_url", "")).rstrip("/"),
        token=read_token(config_dir),
        timeout_seconds=float(section.get("timeout_seconds", 15.0)),
        batch_size=min(int(section.get("batch_size", MAX_POINTS_PER_REQUEST)),
                       MAX_POINTS_PER_REQUEST),
    )


# =====================================================================
# Client
# =====================================================================


class OntoplanoClient(Protocol):
    """What the sync worker needs. Swappable so the worker can be tested
    against a mock, and so sync-disabled costs nothing at runtime."""

    async def declare_stream(self) -> bool: ...

    async def push_points(self, points: Sequence[Point]) -> PushResult: ...

    async def close(self) -> None: ...


class DisabledClient:
    """Used when sync is off or unconfigured. Never touches the network."""

    async def declare_stream(self) -> bool:
        return False

    async def push_points(self, points: Sequence[Point]) -> PushResult:
        raise ServerError("ontoplano sync is disabled")

    async def close(self) -> None:
        return None


class HttpOntoplanoClient:
    """Implements the contract in the integration brief, section 2."""

    def __init__(self, config: OntoplanoConfig, *, session: Optional[aiohttp.ClientSession] = None):
        self.config = config
        self._session = session
        self._owns_session = session is None

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=self.config.timeout_seconds),
                headers={
                    "Authorization": f"Bearer {self.config.token}",
                    "Content-Type": "application/json",
                },
            )
            self._owns_session = True
        return self._session

    def _url(self, path: str) -> str:
        return f"{self.config.base_url}{path}"

    @staticmethod
    async def _raise_for_status(response: aiohttp.ClientResponse) -> None:
        if response.status < 400:
            return

        try:
            body = await response.json()
            error = body.get("error", {})
            message = error.get("message") or error.get("code") or str(body)
        except (aiohttp.ContentTypeError, json.JSONDecodeError, ValueError):
            message = (await response.text())[:300]

        status = response.status
        if status == 401:
            raise AuthError(message, status=status)
        if status == 402:
            raise PlanLimitError(message, status=status)
        if status == 403:
            raise ForbiddenError(message, status=status)
        if status == 404:
            raise StreamMissingError(message, status=status)
        if status == 429:
            retry_after_header = response.headers.get("Retry-After")
            retry_after: Optional[float] = None
            if retry_after_header:
                try:
                    retry_after = float(retry_after_header)
                except ValueError:
                    retry_after = None
            raise RateLimitedError(message, retry_after=retry_after)
        if 500 <= status < 600:
            raise ServerError(message, status=status)
        # 400/422 and friends: the payload is wrong and will never be accepted.
        error = OntoplanoError(message, status=status)
        error.retryable = False
        raise error

    async def declare_stream(self) -> bool:
        """Upsert the stream. Idempotent -- safe at every startup, and an
        already-existing stream is not an error."""
        session = await self._get_session()
        try:
            async with session.post(
                self._url("/api/v1/streams"), json=STREAM_DECLARATION
            ) as response:
                if response.status == 409:
                    logger.debug("Stream %s already declared", STREAM_SLUG)
                    return False
                await self._raise_for_status(response)
                body = await response.json()
                created = bool(body.get("created", False))
                logger.info(
                    "Stream %s declared (created=%s)", body.get("slug", STREAM_SLUG), created
                )
                return created
        except aiohttp.ClientError as exc:
            raise ServerError(f"transport failure declaring stream: {exc}") from exc

    async def push_points(self, points: Sequence[Point]) -> PushResult:
        if not points:
            return PushResult(accepted=[], duplicates=[], rejected=[])
        if len(points) > MAX_POINTS_PER_REQUEST:
            raise ValueError(
                f"batch of {len(points)} exceeds the {MAX_POINTS_PER_REQUEST}-point limit"
            )

        session = await self._get_session()
        payload = {"points": [point.to_json() for point in points]}
        url = self._url(f"/api/v1/streams/{STREAM_SLUG}/points")

        try:
            async with session.post(url, json=payload) as response:
                await self._raise_for_status(response)
                body = await response.json()
        except aiohttp.ClientError as exc:
            raise ServerError(f"transport failure pushing points: {exc}") from exc

        return self._interpret(points, body)

    @staticmethod
    def _interpret(points: Sequence[Point], body: dict) -> PushResult:
        """Turn the server's counts into per-point outcomes.

        The response reports `accepted` and `duplicates` as *counts* but only
        names the rejects, so anything not explicitly rejected was stored. That
        is the only reading that keeps the queue correct under partial success.
        """
        rejected_entries = body.get("rejected") or []
        rejected: list[tuple[str, str]] = []
        rejected_ids: set[str] = set()
        for entry in rejected_entries:
            if not isinstance(entry, dict):
                continue
            external_id = str(entry.get("external_id", ""))
            reason = str(entry.get("reason", "unspecified"))
            if external_id:
                rejected_ids.add(external_id)
                rejected.append((external_id, reason))

        stored = [point.external_id for point in points if point.external_id not in rejected_ids]

        # Split stored into accepted/duplicates for reporting only; both are
        # treated as delivered.
        duplicate_count = int(body.get("duplicates", 0) or 0)
        duplicate_count = max(0, min(duplicate_count, len(stored)))
        accepted = stored[: len(stored) - duplicate_count]
        duplicates = stored[len(stored) - duplicate_count :]

        return PushResult(accepted=accepted, duplicates=duplicates, rejected=rejected)

    async def whoami(self) -> dict:
        """Token introspection. Needs no scope, so it is the right way to check
        that setup worked and to learn the user's timezone."""
        session = await self._get_session()
        try:
            async with session.get(self._url("/api/v1/me")) as response:
                await self._raise_for_status(response)
                return await response.json()
        except aiohttp.ClientError as exc:
            raise ServerError(f"transport failure calling /me: {exc}") from exc

    async def fetch_schedule(self, days: int = 7) -> dict:
        """Upcoming planner occurrences -- what alarms are derived from.

        `at_local` is naive wall-clock; the `timezone` field says how to read it.
        Deliberately not converted here: the caller schedules against local time.
        """
        session = await self._get_session()
        try:
            async with session.get(
                self._url("/api/v1/schedule/upcoming"),
                params={"days": max(1, min(int(days), 31))},
            ) as response:
                await self._raise_for_status(response)
                return await response.json()
        except aiohttp.ClientError as exc:
            raise ServerError(f"transport failure reading schedule: {exc}") from exc

    async def fetch_points(self, since: str, limit: int = 500) -> list[dict]:
        """Read points back, for an explicit reconciliation check.

        Not used by the sync worker, and not part of normal operation: it needs
        the `streams:read` scope, which massalarme deliberately does not request
        (it is the source of truth for its own readings). Only useful if the user
        issues a wider token on purpose.
        """
        session = await self._get_session()
        url = self._url(f"/api/v1/streams/{STREAM_SLUG}/points")
        try:
            async with session.get(url, params={"since": since, "limit": limit}) as response:
                await self._raise_for_status(response)
                body = await response.json()
        except aiohttp.ClientError as exc:
            raise ServerError(f"transport failure reading points: {exc}") from exc
        return list(body.get("points", []))

    async def close(self) -> None:
        if self._session and self._owns_session and not self._session.closed:
            await self._session.close()


def build_client(config: OntoplanoConfig) -> OntoplanoClient:
    if not config.usable:
        if config.enabled:
            logger.warning(
                "ontoplano sync is enabled but %s -- staying disabled",
                "base_url is empty" if not config.base_url else "no token file found",
            )
        return DisabledClient()
    logger.info("ontoplano sync enabled -> %s", config.base_url)
    return HttpOntoplanoClient(config)
