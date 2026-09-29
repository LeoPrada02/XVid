"""Where XVid listens, and the HTTPS certificate that lets the phone connect over Wi-Fi."""

import socket
from pathlib import Path

CERTS = Path(__file__).resolve().parent.parent / "certs"
CERT_FILE = CERTS / "xvid.pem"
KEY_FILE = CERTS / "xvid-key.pem"
CA_FILE = CERTS / "xvid-ca.crt"
IP_FILE = CERTS / "ip.txt"  # the Wi-Fi address the certificate was made for

HTTPS_PORT = 8443
HTTP_PORT = 8000


def lan_ip() -> str:
    """This PC's address on the home network (the one the default route uses)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 80))  # no packet is sent; this only picks the outgoing interface
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def cert_ip() -> str | None:
    return IP_FILE.read_text().strip() if IP_FILE.exists() else None


def https_ready() -> bool:
    return CERT_FILE.exists() and KEY_FILE.exists()
