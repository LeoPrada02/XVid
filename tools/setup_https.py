"""One-time setup so the phone can use XVid over the home Wi-Fi with trusted HTTPS.

Android only lets XVid appear in the Share menu if the page is served over HTTPS
the phone trusts. mkcert creates a small private certificate authority (CA) on this
PC and a certificate for this PC's Wi-Fi address signed by it. The phone installs
the CA's *public* certificate once and then trusts XVid.

Run again if the PC's Wi-Fi address changes.
"""

import shutil
import socket
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app.network import CA_FILE, CERT_FILE, CERTS, HTTPS_PORT, IP_FILE, KEY_FILE, lan_ip  # noqa: E402

mkcert = shutil.which("mkcert")
if not mkcert:
    sys.exit("mkcert not found. Install it with:  winget install FiloSottile.mkcert\n"
             "then open a NEW terminal and run this again.")

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
shutil.copyfile(caroot / "rootCA.pem", CA_FILE)  # public part only; rootCA-key.pem never leaves mkcert's folder
IP_FILE.write_text(ip)

print(f"""
Done. Next:

1. Allow the phone through the Windows firewall (in an ADMIN PowerShell, once):
     New-NetFirewallRule -DisplayName XVid -Direction Inbound -Protocol TCP -LocalPort {HTTPS_PORT} -Action Allow -Profile Private
   Your Wi-Fi must be a "Private" network: Settings > Network & internet > Wi-Fi > (your network).

2. Start XVid:  .\\run.ps1

3. On the phone (same Wi-Fi), install the certificate:
     - In Chrome open  https://{ip}:{HTTPS_PORT}/ca.crt
       (it warns the first time: Advanced > Proceed). The file downloads.
     - Settings > Security & privacy > More security settings > Encryption & credentials
       > Install a certificate > CA certificate > pick xvid-ca.crt.

4. On the phone open  https://{ip}:{HTTPS_PORT}  in Chrome, log in, then
   menu > Add to Home screen > Install.  Now X > Share > XVid works.

Tip: reserve {ip} for this PC in your router (DHCP reservation) so it never changes.
""")
