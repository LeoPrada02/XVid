"""This PC's XVid identity and the other XVid PCs it knows, kept in xvid.json in XVid's data folder.

One PC is the *home*: phones install the app from it, and other PCs join it (tools/configure.py).
Joined PCs share the home's certificate authority and token, so one phone app works with all.
"""

import json
import os
import re
import socket
import subprocess
import threading
import uuid
from pathlib import Path

from app.network import CERTS, CONFIG_FILE, HTTPS_PORT, ROOT, current_ip

_lock = threading.Lock()

_REPO = re.compile(r"[\w.-]+/[\w.-]+")
_GITHUB_REMOTE = re.compile(r"github\.com[:/]([\w.-]+/[\w.-]+?)(?:\.git)?/?$")


def load() -> dict:
    config = {"name": socket.gethostname(), "home": None, "peers": []}
    if CONFIG_FILE.exists():
        config.update(json.loads(CONFIG_FILE.read_text(encoding="utf-8")))
    if not config.get("id"):  # identifies this PC to phones and other PCs, whatever its address
        config["id"] = uuid.uuid4().hex
        save(config)
    return config


def save(config: dict) -> None:
    with _lock:
        CONFIG_FILE.write_text(json.dumps(config, indent=2), encoding="utf-8")


def self_url() -> str:
    return f"https://{current_ip()}:{HTTPS_PORT}"


def upsert_peer(config: dict, name: str, url: str, pc_id: str | None = None) -> None:
    """Remember another PC, or update its name and address. Its id identifies it; PCs from before ids
    have none, and their address identifies them."""
    if url == self_url() or (pc_id and pc_id == config.get("id")):
        return

    def same(peer: dict) -> bool:
        return (pc_id and peer.get("id") == pc_id) or (peer["url"] == url and not (pc_id and peer.get("id")))

    peer = {"id": pc_id, "name": name, "url": url} if pc_id else {"name": name, "url": url}
    config["peers"] = [p for p in config["peers"] if not same(p)] + [peer]


def caroot() -> Path:
    """mkcert's folder, which holds the certificate authority (setup_https.py records it)."""
    if os.environ.get("CAROOT"):
        return Path(os.environ["CAROOT"])
    recorded = CERTS / "caroot.txt"
    if recorded.exists():
        return Path(recorded.read_text().strip())
    return Path(os.environ.get("LOCALAPPDATA", "")) / "mkcert"


def releases_repo() -> str | None:
    """The GitHub repo ("owner/name") whose Releases hold the phone app, or None if unknown.

    Kept out of the code (the repo is public): XVID_RELEASES_REPO, else "releases_repo" in xvid.json
    (for a ZIP download), else the git remote this folder was cloned from.
    """
    if "XVID_RELEASES_REPO" in os.environ:
        repo = os.environ["XVID_RELEASES_REPO"].strip()
    else:
        repo = load().get("releases_repo") or _git_remote_repo()
    if not repo or not _REPO.fullmatch(repo) or ".." in repo:
        return None
    return repo


def _git_remote_repo() -> str | None:
    try:
        remote = subprocess.run(["git", "-C", str(ROOT), "remote", "get-url", "origin"],
                                capture_output=True, text=True, timeout=5).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    match = _GITHUB_REMOTE.search(remote)
    return match.group(1) if match else None
