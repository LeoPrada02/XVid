"""This PC's XVid identity and the other XVid PCs it knows, kept in xvid.json in XVid's data folder.

One PC is the *home*: phones install the app from it, and other PCs join it (tools/configure.py).
Joined PCs share the home's certificate authority and token, so one phone app works with all.
"""

import json
import os
import socket
import threading
from pathlib import Path

from app.network import CERTS, CONFIG_FILE, HTTPS_PORT, cert_ip, lan_ip

_lock = threading.Lock()


def load() -> dict:
    config = {"name": socket.gethostname(), "home": None, "peers": []}
    if CONFIG_FILE.exists():
        config.update(json.loads(CONFIG_FILE.read_text(encoding="utf-8")))
    return config


def save(config: dict) -> None:
    with _lock:
        CONFIG_FILE.write_text(json.dumps(config, indent=2), encoding="utf-8")


def self_url() -> str:
    return f"https://{cert_ip() or lan_ip()}:{HTTPS_PORT}"


def upsert_peer(config: dict, name: str, url: str) -> None:
    """Remember another PC (or update its name). Its address identifies it."""
    if url == self_url():
        return
    config["peers"] = [p for p in config["peers"] if p["url"] != url] + [{"name": name, "url": url}]


def caroot() -> Path:
    """mkcert's folder, which holds the certificate authority (setup_https.py records it)."""
    if os.environ.get("CAROOT"):
        return Path(os.environ["CAROOT"])
    recorded = CERTS / "caroot.txt"
    if recorded.exists():
        return Path(recorded.read_text().strip())
    return Path(os.environ.get("LOCALAPPDATA", "")) / "mkcert"
