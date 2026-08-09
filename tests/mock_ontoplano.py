"""A local stand-in for the ontoplano streams API.

The real endpoints do not exist yet (recipe R33 in ontoplano's publishing plan),
so this implements the contract as documented and lets tests drive every failure
branch. If the shipped API diverges from this file, this file is the bug report.
"""

from __future__ import annotations

from typing import Optional

from aiohttp import web

VALID_TOKEN = "test-token"


class MockOntoplano:
    """Implements POST /api/v1/streams, POST+GET .../points.

    Fault injection is per-request-count so tests can say "fail the first two
    pushes, then succeed" without patching internals.
    """

    def __init__(self, token: str = VALID_TOKEN):
        self.token = token
        self.streams: dict[str, dict] = {}
        # slug -> external_id -> point
        self.points: dict[str, dict[str, dict]] = {}

        self.declare_calls = 0
        self.push_calls = 0
        self.received_batches: list[list[dict]] = []

        # Fault injection knobs.
        self.fail_pushes_with: list[Optional[int]] = []
        self.retry_after: Optional[str] = None
        self.reject_ids: dict[str, str] = {}
        self.drop_response_after_storing = 0

    # ── Wiring ───────────────────────────────────────────────────────

    def build_app(self) -> web.Application:
        app = web.Application()
        app.router.add_post("/api/v1/streams", self.declare_stream)
        app.router.add_post("/api/v1/streams/{slug}/points", self.push_points)
        app.router.add_get("/api/v1/streams/{slug}/points", self.get_points)
        return app

    def _auth(self, request: web.Request) -> Optional[web.Response]:
        header = request.headers.get("Authorization", "")
        if header != f"Bearer {self.token}":
            return web.json_response(
                {"error": {"code": "unauthorized", "message": "bad or revoked token"}},
                status=401,
            )
        return None

    # ── Handlers ─────────────────────────────────────────────────────

    async def declare_stream(self, request: web.Request) -> web.Response:
        unauthorized = self._auth(request)
        if unauthorized:
            return unauthorized

        self.declare_calls += 1
        body = await request.json()
        slug = body["slug"]
        created = slug not in self.streams
        self.streams[slug] = body
        self.points.setdefault(slug, {})
        return web.json_response({"slug": slug, "created": created})

    async def push_points(self, request: web.Request) -> web.Response:
        unauthorized = self._auth(request)
        if unauthorized:
            return unauthorized

        self.push_calls += 1

        if self.fail_pushes_with:
            status = self.fail_pushes_with.pop(0)
            if status is not None:
                headers = {}
                if status == 429 and self.retry_after:
                    headers["Retry-After"] = self.retry_after
                return web.json_response(
                    {"error": {"code": str(status), "message": f"injected {status}"}},
                    status=status,
                    headers=headers,
                )

        slug = request.match_info["slug"]
        if slug not in self.streams:
            return web.json_response(
                {"error": {"code": "not_found", "message": "stream not declared"}},
                status=404,
            )

        body = await request.json()
        incoming = body.get("points", [])
        self.received_batches.append(incoming)

        stored = self.points.setdefault(slug, {})
        accepted = 0
        duplicates = 0
        rejected = []

        for point in incoming:
            external_id = point.get("external_id")
            if not external_id or "at" not in point or "value" not in point:
                rejected.append(
                    {"external_id": external_id, "reason": "missing required field"}
                )
                continue
            if external_id in self.reject_ids:
                rejected.append(
                    {"external_id": external_id, "reason": self.reject_ids[external_id]}
                )
                continue
            if external_id in stored:
                duplicates += 1
                continue
            stored[external_id] = point
            accepted += 1

        if self.drop_response_after_storing > 0:
            # Simulate the nightmare case: the server stored everything, then the
            # connection died before the client saw the response.
            self.drop_response_after_storing -= 1
            raise web.HTTPServiceUnavailable(text="connection dropped")

        return web.json_response(
            {"accepted": accepted, "duplicates": duplicates, "rejected": rejected}
        )

    async def get_points(self, request: web.Request) -> web.Response:
        unauthorized = self._auth(request)
        if unauthorized:
            return unauthorized

        slug = request.match_info["slug"]
        since = request.query.get("since", "")
        limit = int(request.query.get("limit", 500))

        matching = [
            point
            for point in self.points.get(slug, {}).values()
            if not since or point["at"] >= since
        ]
        matching.sort(key=lambda point: point["at"])
        return web.json_response(
            {"points": matching[:limit], "has_more": len(matching) > limit}
        )

    # ── Assertions helpers ───────────────────────────────────────────

    def stored_ids(self, slug: str = "massalarme.weight") -> set[str]:
        return set(self.points.get(slug, {}))

    def stored_count(self, slug: str = "massalarme.weight") -> int:
        return len(self.points.get(slug, {}))
