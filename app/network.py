"""Where XVid keeps this PC's state, where it listens, and the HTTPS certificate that lets the phone connect."""

import os
import shutil
import socket
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# This PC's own state (token, certificates, name, other PCs), outside the repo: the repo may be synced
# between PCs (e.g. OneDrive), and each PC needs its own.
DATA = Path(os.environ.get("XVID_DATA") or Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "XVid")
DATA.mkdir(parents=True, exist_ok=True)
for _name in ("certs", "token.txt", "xvid.json"):  # older versions kept these in the repo folder
    if (ROOT / _name).exists() and not (DATA / _name).exists():
        shutil.move(str(ROOT / _name), str(DATA / _name))

TOKEN_FILE = DATA / "token.txt"
CONFIG_FILE = DATA / "xvid.json"
CERTS = DATA / "certs"
CERT_FILE = CERTS / "xvid.pem"
KEY_FILE = CERTS / "xvid-key.pem"
CA_FILE = CERTS / "xvid-ca.crt"
IP_FILE = CERTS / "ip.txt"  # the Wi-Fi address the certificate was made for

HTTPS_PORT = int(os.environ.get("XVID_HTTPS_PORT") or 8443)
HTTP_PORT = int(os.environ.get("XVID_HTTP_PORT") or 8000)


def lan_ip() -> str:
    """This PC's address on the home network (the one the default route uses)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 80))  # no packet is sent; this only picks the outgoing interface
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def current_ip() -> str:
    """This PC's address on the home network now, or the one its certificate was made for while offline."""
    ip = lan_ip()
    return (cert_ip() or ip) if ip.startswith("127.") else ip


def cert_ip() -> str | None:
    return IP_FILE.read_text().strip() if IP_FILE.exists() else None


def https_ready() -> bool:
    return CERT_FILE.exists() and KEY_FILE.exists()
