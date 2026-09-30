"""Media and thumbnail links (for <video>, <img> and downloads, which can't send headers) carry the
media key. It opens media only, never the rest of the API."""

import pytest

from conftest import bearer, login

JPEG = b"\xff\xd8\xff\xe0 a tiny fake jpeg"
VIDEO = b"0123456789" * 100


@pytest.fixture
def keys(pc):
    """Puts one video with a thumbnail in the library; returns the session and media key."""
    body = login(pc.client())
    phone = pc.client(headers=bearer(body["session"]))
    assert phone.post("/api/upload", files={"file": ("clip.mp4", VIDEO, "video/mp4")}).status_code == 200
    assert phone.post("/api/videos/clip.mp4/thumb", files={"file": ("f.jpg", JPEG, "image/jpeg")}).status_code == 200
    return body


def test_media_opens_with_the_media_key(pc, keys):
    res = pc.client().get("/media/clip.mp4", params={"t": keys["media_key"]})
    assert res.status_code == 200 and res.content == VIDEO


def test_thumbnail_opens_with_the_media_key(pc, keys):
    res = pc.client().get("/thumb/clip.jpg", params={"t": keys["media_key"]})
    assert res.status_code == 200 and res.content == JPEG


@pytest.mark.parametrize("path", ["/media/clip.mp4", "/thumb/clip.jpg"])
@pytest.mark.parametrize("params", [{}, {"t": ""}, {"t": "wrong-key"}])
def test_media_links_need_the_media_key(pc, keys, path, params):
    assert pc.client().get(path, params=params).status_code == 401


def test_the_session_is_not_a_media_key(pc, keys):
    assert pc.client().get("/media/clip.mp4", params={"t": keys["session"]}).status_code == 401


def test_media_also_opens_with_the_session(pc, keys):
    assert pc.client().get("/media/clip.mp4", headers=bearer(keys["session"])).status_code == 200


@pytest.mark.parametrize("path", ["/api/videos", "/api/me", "/api/jobs", "/api/pcs"])
def test_the_media_key_doesnt_open_the_api(pc, keys, path):
    key = keys["media_key"]
    assert pc.client().get(path, params={"t": key}).status_code == 401
    assert pc.client().get(path, headers=bearer(key)).status_code == 401
    assert pc.client(cookies={"xvid_session": key}).get(path).status_code == 401


def test_the_media_key_cant_change_the_library(pc, keys):
    client = pc.client()
    key = keys["media_key"]
    assert client.delete("/api/videos/clip.mp4", params={"t": key}).status_code == 401
    assert client.post("/api/upload", params={"t": key},
                       files={"file": ("x.mp4", b"x", "video/mp4")}).status_code == 401
    assert client.get("/media/clip.mp4", params={"t": key}).status_code == 200


def test_media_supports_seeking(pc, keys):
    res = pc.client().get("/media/clip.mp4", params={"t": keys["media_key"]}, headers={"Range": "bytes=10-19"})
    assert res.status_code == 206 and res.content == VIDEO[10:20]


def test_save_to_phone_is_a_download(pc, keys):
    res = pc.client().get("/media/clip.mp4", params={"t": keys["media_key"], "download": "1"})
    assert res.status_code == 200
    assert res.headers["content-disposition"].startswith("attachment")


def test_media_links_only_open_library_videos(pc, keys):
    client = pc.client()
    key = keys["media_key"]
    (pc.library / "notes.txt").write_text("not a video")
    assert client.get("/media/notes.txt", params={"t": key}).status_code == 404
    assert client.get("/media/missing.mp4", params={"t": key}).status_code == 404
    assert client.get("/media/..%2Fdata%2Fxvid.json", params={"t": key}).status_code == 404
    assert client.get("/thumb/clip.json", params={"t": key}).status_code == 404  # video info, not a thumbnail
    assert client.get("/thumb/missing.jpg", params={"t": key}).status_code == 404
