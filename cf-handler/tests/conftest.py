import pytest
import pytest_asyncio
from fastapi.testclient import TestClient

import os
from urllib.parse import urlparse

TEST_URL = os.environ.get("CF_TEST_URL", "https://cloudflare.hackmap.win/")
TEST_HOST = urlparse(TEST_URL).netloc
EXPECTED_SUCCESS_TEXT = os.environ.get("CF_TEST_EXPECTED_TEXT", "OK YOU PASSED IT")
SERVER_URL = "http://localhost:8000"
CF_COOKIE_PREFIXES = ("cf_", "__cf")
MIN_HTML_LENGTH = 50

@pytest.fixture
def test_url():
    \
    return TEST_URL

@pytest.fixture
def expected_text():
    \
    return EXPECTED_SUCCESS_TEXT

@pytest.fixture
def test_host():
    \
    return TEST_HOST

@pytest.fixture
def server_url():
    \
    return SERVER_URL

@pytest.fixture
def cf_cookie_prefixes():
    \
    return CF_COOKIE_PREFIXES

@pytest.fixture
def min_html_length():
    \
    return MIN_HTML_LENGTH

@pytest.fixture
def client():
    \
    from cf_handler.server.app import create_app
    with TestClient(create_app()) as c:
        yield c

@pytest_asyncio.fixture
async def handler():
    \
    from cf_handler.browser.pool import BrowserPool
    from cf_handler.core.handler import CFhandler
    instance = CFhandler(log=True, pool=BrowserPool(1), cache_enabled=True)
    await instance.start()
    yield instance
    await instance.cleanup()

@pytest_asyncio.fixture(scope="session", loop_scope="session")
async def shared_cookies():
    from cf_handler.browser.pool import BrowserPool
    from cf_handler.core.handler import CFhandler
    instance = CFhandler(log=True, pool=BrowserPool(1))
    await instance.start()
    try:
        return await instance.get_or_generate_cookies(TEST_URL)
    finally:
        await instance.cleanup()

@pytest_asyncio.fixture(scope="session", loop_scope="session")
async def shared_html():
    from cf_handler.browser.pool import BrowserPool
    from cf_handler.core.handler import CFhandler
    instance = CFhandler(log=True, pool=BrowserPool(1))
    await instance.start()
    try:
        return await instance.get_or_generate_html(TEST_URL)
    finally:
        await instance.cleanup()

class _FakeStates:
    is_alive = True

class _FakeBrowser:
    process_id = None

    def __init__(self, *a, **k):
        self.states = _FakeStates()

    def quit(self, *a, **k):
        self.states = type("S", (), {"is_alive": False})()

@pytest.fixture(autouse=True)
def _no_real_browsers(request, monkeypatch):
    \
    if request.node.get_closest_marker("live"):
        return
    import cf_handler.browser.pool as pool_mod
    monkeypatch.setattr(pool_mod, "launch_chromium", _FakeBrowser)
