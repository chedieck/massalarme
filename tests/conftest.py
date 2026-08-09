import sys
from pathlib import Path

import pytest
import pytest_asyncio
from aiohttp import web

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

from mock_ontoplano import MockOntoplano, VALID_TOKEN  # noqa: E402
from ontoplano import HttpOntoplanoClient, OntoplanoConfig  # noqa: E402
from store import WeighInStore  # noqa: E402


@pytest.fixture
def store(tmp_path) -> WeighInStore:
    store = WeighInStore(tmp_path / "weights.db")
    store.init()
    return store


@pytest.fixture
def mock_server() -> MockOntoplano:
    return MockOntoplano()


@pytest_asyncio.fixture
async def server_url(mock_server, unused_tcp_port):
    runner = web.AppRunner(mock_server.build_app(), access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", unused_tcp_port)
    await site.start()
    try:
        yield f"http://127.0.0.1:{unused_tcp_port}"
    finally:
        await runner.cleanup()


@pytest_asyncio.fixture
async def client(server_url):
    ontoplano_client = HttpOntoplanoClient(
        OntoplanoConfig(
            enabled=True, base_url=server_url, token=VALID_TOKEN, timeout_seconds=5
        )
    )
    try:
        yield ontoplano_client
    finally:
        await ontoplano_client.close()


@pytest.fixture
def unused_tcp_port():
    import socket

    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]
