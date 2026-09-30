"""Several PCs: the PC list, joining the home PC with an "Add a PC" code, and announcing a PC's address."""

import base64
import json

from conftest import HTTPS_PORT, LOCAL, OTHER_PC, PC_IP, PC_URL, TOKEN, bearer, login

LAPTOP_URL = "https://192.0.2.31:8443"


def join_code(pc) -> str:
    """The secret part of an "Add a PC" code (the rest pins the home PC's address and certificate)."""
    res = pc.client(LOCAL).post("/api/pc-code")
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["expires_in"] == 600
    code = body["code"]
    assert code.startswith("XVID-")
    raw = code.removeprefix("XVID-")
    payload = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
    assert (payload["h"], payload["p"]) == (PC_IP, HTTPS_PORT)
    assert len(payload["f"]) == 64  # certificate fingerprint (SHA-256, hex)
    return payload["c"]


def join(pc, code: str, name: str = "laptop", url: str = LAPTOP_URL):
    return pc.client(OTHER_PC).post("/api/join", json={"code": code, "name": name, "url": url})


def pcs(pc) -> list[dict]:
    res = pc.client(LOCAL).get("/api/pcs")
    assert res.status_code == 200
    return res.json()


def test_a_new_pc_lists_only_itself_as_home(phone_ready):
    [this] = pcs(phone_ready)
    assert this["url"] == PC_URL and this["home"] is True and this["name"]


def test_pc_list_needs_login(phone_ready):
    assert phone_ready.client().get("/api/pcs").status_code == 401
    session = login(phone_ready.client())["session"]
    assert phone_ready.client().get("/api/pcs", headers=bearer(session)).status_code == 200


def test_joining_hands_over_the_token_and_certificate_authority(phone_ready):
    res = join(phone_ready, join_code(phone_ready))
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["token"] == TOKEN
    assert body["home"] == PC_URL
    assert body["ca_cert"] == "fake root ca cert" and body["ca_key"] == "fake root ca key"
    assert {"name": "laptop", "url": LAPTOP_URL, "home": False} in body["pcs"]


def test_a_joined_pc_appears_in_the_list(phone_ready):
    join(phone_ready, join_code(phone_ready))
    listed = pcs(phone_ready)
    assert listed[0]["url"] == PC_URL
    assert listed[1:] == [{"name": "laptop", "url": LAPTOP_URL, "home": False}]


def test_a_join_code_works_only_once(phone_ready):
    code = join_code(phone_ready)
    assert join(phone_ready, code).status_code == 200
    assert join(phone_ready, code, name="intruder", url="https://192.0.2.99:8443").status_code == 401
    assert [p["name"] for p in pcs(phone_ready)[1:]] == ["laptop"]


def test_a_join_code_expires_after_ten_minutes(phone_ready):
    code = join_code(phone_ready)
    phone_ready.clock.advance(601)
    assert join(phone_ready, code).status_code == 401
    assert len(pcs(phone_ready)) == 1


def test_wrong_join_codes_count_towards_the_lockout(phone_ready):
    for _ in range(5):
        assert join(phone_ready, "made-up").status_code == 401
    assert join(phone_ready, join_code(phone_ready)).status_code == 429


def test_only_the_pc_itself_can_create_join_codes(phone_ready):
    phone = phone_ready.client()
    session = login(phone)["session"]
    assert phone.post("/api/pc-code", headers=bearer(session)).status_code == 403


def test_no_join_codes_before_phone_access_is_set_up(pc):
    assert pc.client(LOCAL).post("/api/pc-code").status_code == 409


def test_announce_adds_and_updates_a_pc(phone_ready):
    session = login(phone_ready.client())["session"]
    other = phone_ready.client(OTHER_PC)
    res = other.post("/api/pcs/announce", json={"name": "laptop", "url": LAPTOP_URL}, headers=bearer(session))
    assert res.status_code == 200
    assert {"name": "laptop", "url": LAPTOP_URL, "home": False} in res.json()

    # Same address, new name: still one entry.
    res = other.post("/api/pcs/announce", json={"name": "renamed", "url": LAPTOP_URL}, headers=bearer(session))
    assert [p for p in res.json() if p["url"] == LAPTOP_URL] == [{"name": "renamed", "url": LAPTOP_URL, "home": False}]
    assert pcs(phone_ready) == res.json()


def test_announcing_this_pcs_own_address_is_ignored(phone_ready):
    session = login(phone_ready.client())["session"]
    res = phone_ready.client(OTHER_PC).post("/api/pcs/announce", json={"name": "impostor", "url": PC_URL},
                                            headers=bearer(session))
    assert res.status_code == 200
    assert len(res.json()) == 1 and res.json()[0]["name"] != "impostor"


def test_announce_needs_login(phone_ready):
    res = phone_ready.client(OTHER_PC).post("/api/pcs/announce", json={"name": "laptop", "url": LAPTOP_URL})
    assert res.status_code == 401
    assert len(pcs(phone_ready)) == 1
