"""Pairing a phone: the PC makes a one-time code (shown as a QR code), and the phone redeems it to log in."""

from urllib.parse import parse_qs, urlparse

from conftest import LOCAL, PC_IP, PC_URL, bearer, login


def create_code(pc) -> str:
    res = pc.client(LOCAL).post("/api/pair")
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["expires_in"] == 600
    assert body["svg"].startswith("<")
    url = urlparse(body["url"])
    assert url.scheme == "http" and url.hostname == PC_IP and url.path == "/setup"
    assert url.query == ""  # the code travels in the #fragment, never sent over plain HTTP
    fragment = parse_qs(url.fragment)
    assert fragment["pc"] == [PC_URL]
    return fragment["pair"][0]


def redeem(client, code: str):
    return client.post("/api/pair/redeem", json={"code": code})


def test_redeeming_a_code_logs_the_phone_in(phone_ready):
    code = create_code(phone_ready)
    phone = phone_ready.client()
    res = redeem(phone, code)
    assert res.status_code == 200
    body = res.json()
    assert body["ok"] is True
    assert phone_ready.client().get("/api/me", headers=bearer(body["session"])).status_code == 200


def test_pairing_gives_the_same_session_as_the_token(phone_ready):
    paired = redeem(phone_ready.client(), create_code(phone_ready)).json()
    logged_in = login(phone_ready.client())
    assert (paired["session"], paired["media_key"]) == (logged_in["session"], logged_in["media_key"])


def test_a_code_works_only_once(phone_ready):
    code = create_code(phone_ready)
    assert redeem(phone_ready.client(), code).status_code == 200
    assert redeem(phone_ready.client(), code).status_code == 401


def test_a_code_expires_after_ten_minutes(phone_ready):
    code = create_code(phone_ready)
    phone_ready.clock.advance(601)
    assert redeem(phone_ready.client(), code).status_code == 401


def test_a_code_still_works_just_before_ten_minutes(phone_ready):
    code = create_code(phone_ready)
    phone_ready.clock.advance(599)
    assert redeem(phone_ready.client(), code).status_code == 200


def test_unknown_code_is_rejected(phone_ready):
    assert redeem(phone_ready.client(), "made-up").status_code == 401


def test_wrong_codes_count_towards_the_lockout(phone_ready):
    phone = phone_ready.client()
    for _ in range(5):
        assert redeem(phone, "made-up").status_code == 401
    assert redeem(phone, create_code(phone_ready)).status_code == 429


def test_only_the_pc_itself_can_create_codes(phone_ready):
    phone = phone_ready.client()
    session = login(phone)["session"]
    assert phone.post("/api/pair", headers=bearer(session)).status_code == 403
    assert phone_ready.client().post("/api/pair").status_code == 401


def test_no_codes_before_phone_access_is_set_up(pc):
    assert pc.client(LOCAL).post("/api/pair").status_code == 409
