"""Logging in with the token, and the lockout after repeated wrong attempts."""

from conftest import LOCAL, OTHER_PC, TOKEN, bearer, login


def test_api_needs_login(pc):
    phone = pc.client()
    assert phone.get("/api/videos").status_code == 401
    assert phone.get("/api/me").status_code == 401


def test_right_token_opens_the_api(pc):
    phone = pc.client()
    body = login(phone)
    assert body["ok"] is True and body["session"] and body["media_key"]
    # The session works as a header (the phone app) ...
    assert pc.client().get("/api/me", headers=bearer(body["session"])).status_code == 200
    # ... and as the cookie the login set.
    assert phone.get("/api/me").status_code == 200


def test_token_is_trimmed(pc):
    login(pc.client(), f"  {TOKEN}\n")


def test_wrong_token_is_rejected(pc):
    phone = pc.client()
    res = phone.post("/api/login", json={"token": "wrong"})
    assert res.status_code == 401
    assert "xvid_session" not in phone.cookies
    assert phone.get("/api/me").status_code == 401


def test_wrong_session_is_rejected(pc):
    assert pc.client().get("/api/me", headers=bearer("not-the-session")).status_code == 401


def test_lockout_after_five_wrong_tokens(pc):
    phone = pc.client()
    for _ in range(5):
        assert phone.post("/api/login", json={"token": "wrong"}).status_code == 401
    # Blocked now, even with the right token.
    assert phone.post("/api/login", json={"token": TOKEN}).status_code == 429
    # Other devices aren't affected.
    login(pc.client(OTHER_PC))


def test_lockout_ends_after_five_minutes(pc):
    phone = pc.client()
    for _ in range(5):
        phone.post("/api/login", json={"token": "wrong"})
    pc.clock.advance(299)
    assert phone.post("/api/login", json={"token": TOKEN}).status_code == 429
    pc.clock.advance(2)
    login(phone)


def test_right_token_clears_earlier_failures(pc):
    phone = pc.client()
    for _ in range(4):
        phone.post("/api/login", json={"token": "wrong"})
    login(phone)
    for _ in range(4):
        assert phone.post("/api/login", json={"token": "wrong"}).status_code == 401
    login(phone)


def test_the_pcs_own_browser_needs_no_login(pc):
    assert pc.client(LOCAL).get("/api/me").status_code == 200


def test_other_websites_in_the_pcs_browser_need_login(pc):
    local = pc.client(LOCAL)
    assert local.get("/api/me", headers={"Sec-Fetch-Site": "cross-site"}).status_code == 401
