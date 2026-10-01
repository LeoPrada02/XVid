"""The old phone web app is gone (the XVid phone app replaced it): the PC no longer serves its offline
copy, share target, phone setup page and certificate download, or downloads videos for the phone."""

import pytest

from conftest import LOCAL, PHONE, login, bearer

GONE = [
    ("GET", "/sw.js"),
    ("GET", "/manifest.webmanifest"),
    ("GET", "/share?text=https://x.com/someone/status/1"),
    ("GET", "/setup"),
    ("GET", "/ca.crt"),
    ("GET", "/static/sw.js"),
    ("GET", "/static/setup.html"),
    ("GET", "/static/manifest.webmanifest"),
    ("POST", "/api/direct"),
    ("GET", "/api/direct/some-item"),
]


@pytest.mark.parametrize("method,path", GONE)
def test_the_old_phone_web_app_is_gone(phone_ready, method, path):
    session = login(phone_ready.client(PHONE))["session"]
    for client, headers in ((phone_ready.client(LOCAL), {}), (phone_ready.client(PHONE), bearer(session))):
        res = client.request(method, path, headers=headers, json={"url": "https://x.com/someone/status/1"} if method == "POST" else None)
        assert res.status_code == 404, (path, res.status_code)


def test_the_pc_browser_ui_is_still_served(phone_ready):
    page = phone_ready.client(LOCAL).get("/")
    assert page.status_code == 200
    assert "Add a phone" in page.text and "Add a PC" in page.text
    assert 'rel="manifest"' not in page.text
    assert phone_ready.client(LOCAL).get("/static/app.js").status_code == 200
