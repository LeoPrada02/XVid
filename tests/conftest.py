"""Test harness for the PC's HTTP API.

Each test gets a fresh copy of the app, started in-process with FastAPI's TestClient, with:
  - a temporary PC library (XVID_LIBRARY) and data folder (XVID_DATA),
  - a fake yt-dlp that "downloads" by writing a small file, so nothing touches the network,
  - a clock the test can move forward, so expiry is tested without sleeping.

Tests talk to the app only over HTTP. The fixtures below only set things up around it.
"""

import importlib
import sys
import threading
import time
import types
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

REPO = Path(__file__).resolve().parent.parent
TOKEN = "test-token-not-a-secret"
PC_IP = "192.0.2.10"  # documentation address (TEST-NET-1), never a real network
LOCAL = ("127.0.0.1", 50000)  # a browser on the PC itself
PHONE = ("192.0.2.20", 50000)  # a phone on the home Wi-Fi
OTHER_PC = ("192.0.2.30", 50000)

# Older versions kept these in the repo folder; the app moves them into its data folder on start.
# Refuse to run rather than let a test move a real token or certificates into a temp folder.
for _name in ("certs", "token.txt", "xvid.json"):
    if (REPO / _name).exists():
        raise pytest.UsageError(f"{REPO / _name} exists: start XVid once (it moves it) before running the tests")


class Clock:
    """Stands in for time.time() inside the app. Starts at the real time; advance() moves it."""

    def __init__(self) -> None:
        self.now = time.time()

    def time(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class FakeYoutubeDL:
    """Replaces yt_dlp.YoutubeDL. A "download" writes a small video (and thumbnail) where yt-dlp would."""

    post_id = "1234567890"
    uploader_id = "someone"
    fail_with: str | None = None  # set to make every download fail with this message
    gate: threading.Event | None = None  # set to hold downloads until the test releases it
    calls: list[str] = []

    def __init__(self, opts: dict | None = None) -> None:
        self.opts = opts or {}

    def __enter__(self):
        return self

    def __exit__(self, *exc) -> None:
        pass

    def close(self) -> None:
        pass

    def extract_info(self, url: str, download: bool = True) -> dict:
        FakeYoutubeDL.calls.append(url)
        if self.gate:
            assert self.gate.wait(10), "the test never released the download"
        if self.fail_with:
            raise RuntimeError(self.fail_with)
        info = {"id": self.post_id, "title": "A test video", "uploader": "Someone",
                "uploader_id": self.uploader_id, "duration": 12.5, "formats": []}
        if download:
            paths = self.opts["paths"]
            video = Path(paths["home"]) / f"{self.uploader_id}_{self.post_id}.mp4"
            for hook in self.opts.get("progress_hooks", []):
                hook({"status": "downloading", "downloaded_bytes": 50, "total_bytes": 100})
            video.write_bytes(b"fake video bytes")
            if self.opts.get("writethumbnail"):
                (Path(paths["thumbnail"]) / f"{video.stem}.jpg").write_bytes(b"\xff\xd8fake jpeg")
            info["requested_downloads"] = [{"filepath": str(video)}]
        return info


@pytest.fixture
def pc(tmp_path, monkeypatch):
    """A fresh XVid PC app. Returns an object with the app, its folders, clock, and client factories."""
    library, data, caroot = tmp_path / "library", tmp_path / "data", tmp_path / "caroot"
    for folder in (library, data, caroot):
        folder.mkdir()
    monkeypatch.setenv("XVID_LIBRARY", str(library))
    monkeypatch.setenv("XVID_DATA", str(data))
    monkeypatch.setenv("XVID_TOKEN", TOKEN)
    monkeypatch.setenv("CAROOT", str(caroot))
    for name in ("XVID_COOKIES_FILE", "XVID_COOKIES_BROWSER", "XVID_PHONE_VIEW", "XVID_HTTPS_PORT", "XVID_HTTP_PORT"):
        monkeypatch.delenv(name, raising=False)

    # A fresh import reads the environment above and starts with empty jobs, codes and lockouts.
    import app
    for module in ("main", "config", "network"):
        sys.modules.pop(f"app.{module}", None)
        if hasattr(app, module):  # `from app import config` would otherwise reuse the old module
            delattr(app, module)
    main = importlib.import_module("app.main")

    clock = Clock()
    monkeypatch.setattr(main, "time", types.SimpleNamespace(time=clock.time, sleep=time.sleep))
    FakeYoutubeDL.fail_with = FakeYoutubeDL.gate = None
    FakeYoutubeDL.calls = []
    monkeypatch.setattr(main, "yt_dlp", types.SimpleNamespace(YoutubeDL=FakeYoutubeDL))

    clients = []

    def client(address=PHONE, **kwargs) -> TestClient:
        c = TestClient(main.app, client=address, **kwargs)
        clients.append(c)
        return c

    ns = types.SimpleNamespace(app=main.app, library=library, data=data, caroot=caroot, clock=clock,
                               ydl=FakeYoutubeDL, client=client)
    yield ns
    if FakeYoutubeDL.gate:
        FakeYoutubeDL.gate.set()
    for c in clients:
        c.close()
    main.executor.shutdown(wait=True)


def enable_phone_access(pc) -> None:
    """What setup.cmd leaves in the data folder: a certificate for the PC's Wi-Fi address, and the
    certificate authority in CAROOT. Only file contents the API reads; nothing is really signed."""
    certs = pc.data / "certs"
    certs.mkdir(exist_ok=True)
    (certs / "xvid.pem").write_text(
        "-----BEGIN CERTIFICATE-----\nZmFrZSBjZXJ0aWZpY2F0ZQ==\n-----END CERTIFICATE-----\n")
    (certs / "xvid-key.pem").write_text("fake key")
    (certs / "xvid-ca.crt").write_text("fake ca")
    (certs / "ip.txt").write_text(PC_IP)
    (pc.caroot / "rootCA.pem").write_text("fake root ca cert")
    (pc.caroot / "rootCA-key.pem").write_text("fake root ca key")


@pytest.fixture
def phone_ready(pc):
    enable_phone_access(pc)
    return pc


def login(client: TestClient, token: str = TOKEN) -> dict:
    """Logs a client in with the token; returns the response body (session and media key)."""
    res = client.post("/api/login", json={"token": token})
    assert res.status_code == 200, res.text
    return res.json()


def bearer(session: str) -> dict:
    return {"Authorization": f"Bearer {session}"}
