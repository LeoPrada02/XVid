"""Create the HTTPS certificate that lets phones use XVid over the home Wi-Fi. Called by setup.ps1.

Android only lets XVid appear in the Share menu if the page is served over HTTPS the phone
trusts. mkcert creates a small private certificate authority (CA) on this PC and a certificate
for this PC's Wi-Fi address signed by it. Phones install the CA's *public* certificate once
(the "Add a phone" page walks them through it).
"""

import shutil
import socket
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app.network import CA_FILE, CERT_FILE, CERTS, IP_FILE, KEY_FILE, lan_ip  # noqa: E402

mkcert = shutil.which("mkcert")
if not mkcert:
    sys.exit("mkcert not found. Install it with:  winget install FiloSottile.mkcert")

ip = lan_ip()
if ip.startswith("127."):
    sys.exit("Couldn't find this PC's Wi-Fi address. Is it connected to the network?")

CERTS.mkdir(exist_ok=True)

print("Installing the XVid certificate authority on this PC (Windows may ask you to confirm)...")
subprocess.run([mkcert, "-install"], check=True)

print(f"Creating a certificate for {ip}...")
subprocess.run([mkcert, "-cert-file", str(CERT_FILE), "-key-file", str(KEY_FILE),
                ip, socket.gethostname(), "localhost", "127.0.0.1"], check=True)

caroot = Path(subprocess.run([mkcert, "-CAROOT"], check=True, capture_output=True, text=True).stdout.strip())
shutil.copyfile(caroot / "rootCA.pem", CA_FILE)  # public part only; rootCA-key.pem stays in mkcert's folder
(CERTS / "caroot.txt").write_text(str(caroot))  # so the home PC can hand the authority to PCs that join it
IP_FILE.write_text(ip)
print(f"Certificate ready for {ip}.")
