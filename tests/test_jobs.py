"""To PC: an X link sent to the PC is downloaded (by the fake yt-dlp) into the PC library."""

import threading
import time

import pytest

from conftest import login

POST_URL = "https://twitter.com/someone/status/1234567890?s=20&t=abc"
CANONICAL = "https://x.com/someone/status/1234567890"
VIDEO = "someone_1234567890.mp4"


def wait_for_job(client, job_id: str) -> dict:
    """Polls the job list until the job finishes (the download runs in a background thread)."""
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        job = next(j for j in client.get("/api/jobs").json() if j["id"] == job_id)
        if job["status"] in ("done", "error"):
            return job
        time.sleep(0.01)
    raise AssertionError(f"job {job_id} didn't finish: {job}")


def test_a_job_downloads_the_video_into_the_pc_library(pc, phone):
    res = phone.post("/api/jobs", json={"url": POST_URL})
    assert res.status_code == 200
    job = res.json()
    assert job["url"] == CANONICAL  # tracking parameters dropped
    assert job["status"] in ("queued", "downloading", "done")

    done = wait_for_job(phone, job["id"])
    assert done["status"] == "done" and done["progress"] == 100
    assert done["files"] == [VIDEO] and done["title"] == "A test video"
    assert pc.ydl.calls == [CANONICAL]

    [video] = phone.get("/api/videos").json()
    assert video["name"] == VIDEO
    assert video["title"] == "A test video"
    assert video["uploader"] == "Someone" and video["uploader_id"] == "someone"
    assert video["source_url"] == CANONICAL
    assert video["duration"] == 12.5
    assert video["thumb"] == "someone_1234567890.jpg"
    assert video["size"] == len(b"fake video bytes")


def test_the_library_folder_only_holds_the_video(pc, phone):
    wait_for_job(phone, phone.post("/api/jobs", json={"url": POST_URL}).json()["id"])
    visible = sorted(p.name for p in pc.library.iterdir() if not p.name.startswith("."))
    assert visible == [VIDEO]


def test_a_link_inside_shared_text_is_found(phone):
    res = phone.post("/api/jobs", json={"url": f"Look at this {POST_URL} wow"})
    assert res.status_code == 200 and res.json()["url"] == CANONICAL


@pytest.mark.parametrize("url", ["", "not a link", "https://example.com/someone/status/1",
                                 "https://x.com/someone", "ftp://x.com/someone/status/1"])
def test_links_that_arent_x_posts_are_rejected(pc, phone, url):
    assert phone.post("/api/jobs", json={"url": url}).status_code == 400
    assert phone.get("/api/jobs").json() == []
    assert pc.ydl.calls == []


def test_a_failed_download_reports_the_error(pc, phone):
    pc.ydl.fail_with = "ERROR: [twitter] 1234567890: No video could be found in this tweet"
    job = wait_for_job(phone, phone.post("/api/jobs", json={"url": POST_URL}).json()["id"])
    assert job["status"] == "error"
    assert job["error"].startswith("No video could be found in this tweet")
    assert phone.get("/api/videos").json() == []


def test_the_same_link_twice_while_downloading_is_one_job(pc, phone):
    pc.ydl.gate = threading.Event()
    first = phone.post("/api/jobs", json={"url": POST_URL}).json()
    second = phone.post("/api/jobs", json={"url": CANONICAL}).json()
    assert second["id"] == first["id"]
    pc.ydl.gate.set()
    wait_for_job(phone, first["id"])
    assert len(phone.get("/api/jobs").json()) == 1


def test_clearing_removes_only_finished_jobs(pc, phone):
    done = wait_for_job(phone, phone.post("/api/jobs", json={"url": POST_URL}).json()["id"])
    pc.ydl.gate = threading.Event()
    running = phone.post("/api/jobs", json={"url": "https://x.com/other/status/42"}).json()
    assert phone.delete("/api/jobs").status_code == 200
    assert [j["id"] for j in phone.get("/api/jobs").json()] == [running["id"]]
    assert done["id"] != running["id"]
    pc.ydl.gate.set()
    wait_for_job(phone, running["id"])


def test_jobs_need_login(pc):
    anonymous = pc.client()
    assert anonymous.post("/api/jobs", json={"url": POST_URL}).status_code == 401
    assert anonymous.get("/api/jobs").status_code == 401
    assert anonymous.delete("/api/jobs").status_code == 401
    assert pc.ydl.calls == []
