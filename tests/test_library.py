"""The PC library: upload, thumbnails, delete and listing."""

import os

import pytest

from conftest import bearer, login

JPEG = b"\xff\xd8\xff\xe0 a tiny fake jpeg"


@pytest.fixture
def phone(pc):
    session = login(pc.client())["session"]
    return pc.client(headers=bearer(session))


def upload(client, filename: str = "holiday.mp4", data: bytes = b"video data"):
    return client.post("/api/upload", files={"file": (filename, data, "video/mp4")})


def names(client) -> list[str]:
    res = client.get("/api/videos")
    assert res.status_code == 200
    return sorted(v["name"] for v in res.json())


def test_an_empty_library(phone):
    assert phone.get("/api/videos").json() == []


def test_upload_adds_the_video_to_the_library(pc, phone):
    res = upload(phone)
    assert res.status_code == 200
    video = res.json()
    assert video["name"] == "holiday.mp4"
    assert video["title"] == "holiday" and video["uploader"] == "Uploaded"
    assert video["size"] == len(b"video data") and video["thumb"] is None
    assert phone.get("/api/videos").json() == [video]
    assert (pc.library / "holiday.mp4").read_bytes() == b"video data"


def test_upload_keeps_filenames_safe_and_unique(phone):
    assert upload(phone, "My clip!.MP4").json()["name"] == "My_clip.mp4"
    assert upload(phone, "My clip!.mp4").json()["name"] == "My_clip_1.mp4"
    assert upload(phone, "My clip!.mp4").json()["name"] == "My_clip_2.mp4"
    assert upload(phone, "../../escape.mp4").json()["name"] == "escape.mp4"
    assert names(phone) == ["My_clip.mp4", "My_clip_1.mp4", "My_clip_2.mp4", "escape.mp4"]


@pytest.mark.parametrize("filename", ["notes.txt", "picture.jpg", "noextension"])
def test_upload_accepts_only_videos(phone, filename):
    assert upload(phone, filename).status_code == 400
    assert names(phone) == []


def test_the_listing_shows_only_videos(pc, phone):
    (pc.library / "dropped in.webm").write_bytes(b"x")
    (pc.library / "readme.txt").write_text("not a video")
    (pc.library / "folder.mp4").mkdir()
    upload(phone)
    assert names(phone) == ["dropped in.webm", "holiday.mp4"]
    dropped = next(v for v in phone.get("/api/videos").json() if v["name"] == "dropped in.webm")
    assert dropped["title"] == "dropped in" and dropped["uploader"] is None


def test_the_listing_is_newest_first(pc, phone):
    for i, name in enumerate(["old.mp4", "new.mp4", "middle.mp4"]):
        (pc.library / name).write_bytes(b"x")
        os.utime(pc.library / name, (1_000_000 + i, [1, 3, 2][i] * 1000))
    assert [v["name"] for v in phone.get("/api/videos").json()] == ["new.mp4", "middle.mp4", "old.mp4"]


def test_setting_a_thumbnail(phone):
    upload(phone)
    res = phone.post("/api/videos/holiday.mp4/thumb", files={"file": ("frame.jpg", JPEG, "image/jpeg")},
                     data={"duration": "7.5"})
    assert res.status_code == 200
    video = res.json()
    assert video["thumb"] == "holiday.jpg" and video["duration"] == 7.5
    assert phone.get("/api/videos").json() == [video]
    assert phone.get("/thumb/holiday.jpg").content == JPEG


def test_a_new_thumbnail_replaces_the_old_one(phone):
    upload(phone)
    phone.post("/api/videos/holiday.mp4/thumb", files={"file": ("a.jpg", JPEG, "image/jpeg")}, data={"duration": "7.5"})
    newer = JPEG + b" newer"
    res = phone.post("/api/videos/holiday.mp4/thumb", files={"file": ("b.jpg", newer, "image/jpeg")},
                     data={"duration": "99"})
    assert res.json()["duration"] == 7.5  # a known duration is kept
    assert phone.get("/thumb/holiday.jpg").content == newer


def test_a_thumbnail_for_a_video_dropped_into_the_folder(pc, phone):
    (pc.library / "dropped.mp4").write_bytes(b"x")
    res = phone.post("/api/videos/dropped.mp4/thumb", files={"file": ("f.jpg", JPEG, "image/jpeg")})
    assert res.status_code == 200 and res.json()["thumb"] == "dropped.jpg"


@pytest.mark.parametrize("data", [b"\x89PNG not a jpeg", b"", b"\xff\xd8" + b"x" * (2 * 1024 * 1024)],
                         ids=["png", "empty", "too-big"])
def test_thumbnails_must_be_small_jpegs(phone, data):
    upload(phone)
    res = phone.post("/api/videos/holiday.mp4/thumb", files={"file": ("f.jpg", data, "image/jpeg")})
    assert res.status_code == 400
    assert phone.get("/api/videos").json()[0]["thumb"] is None


def test_thumbnail_for_a_missing_video(phone):
    res = phone.post("/api/videos/missing.mp4/thumb", files={"file": ("f.jpg", JPEG, "image/jpeg")})
    assert res.status_code == 404


def test_delete_removes_the_video_and_its_thumbnail(pc, phone):
    upload(phone)
    upload(phone, "keep.mp4")
    phone.post("/api/videos/holiday.mp4/thumb", files={"file": ("f.jpg", JPEG, "image/jpeg")})
    assert phone.delete("/api/videos/holiday.mp4").status_code == 200
    assert names(phone) == ["keep.mp4"]
    assert phone.get("/media/holiday.mp4").status_code == 404
    assert phone.get("/thumb/holiday.jpg").status_code == 404
    assert not (pc.library / "holiday.mp4").exists()


def test_delete_a_missing_video(phone):
    assert phone.delete("/api/videos/missing.mp4").status_code == 404


def test_delete_cant_leave_the_library(pc, phone, tmp_path):
    outside = tmp_path / "outside.mp4"
    outside.write_bytes(b"x")
    assert phone.delete("/api/videos/..%2Foutside.mp4").status_code == 404
    assert outside.exists()


def test_the_library_needs_login(pc):
    anonymous = pc.client()
    assert anonymous.get("/api/videos").status_code == 401
    assert upload(anonymous).status_code == 401
    assert anonymous.delete("/api/videos/holiday.mp4").status_code == 401
    res = anonymous.post("/api/videos/holiday.mp4/thumb", files={"file": ("f.jpg", JPEG, "image/jpeg")})
    assert res.status_code == 401
    assert list(pc.library.glob("*.mp4")) == []
