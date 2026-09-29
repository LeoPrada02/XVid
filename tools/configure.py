"""Name this PC, and optionally join the home PC's XVid. Called by setup.ps1.

    configure.py --name "Laptop"                   set the name the phone shows for this PC
    configure.py --name "Laptop" --join XVID-...   also join the home PC (code from its "Add a PC")

Joining downloads the home PC's certificate authority and token over HTTPS, after checking the
home PC's certificate against the fingerprint inside the code. Then this PC's certificate (made
next by setup_https.py) is signed by the same authority, so phones that trust the home PC trust
this one too, and the same login works on both.
"""

import argparse
import base64
import hashlib
import http.client
import json
import shutil
import ssl
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from app import config  # noqa: E402
from app.network import HTTPS_PORT, TOKEN_FILE, lan_ip  # noqa: E402


def decode(code: str) -> dict:
    try:
        raw = code.strip().removeprefix("XVID-")
        return json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
    except ValueError:
        sys.exit("That doesn't look like an XVid code. Copy it again from 'Add a PC' on the home PC.")


def fetch_from_home(code: dict, name: str) -> dict:
    # The home PC's certificate isn't trusted here yet, so check it against the pinned fingerprint instead.
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    conn = http.client.HTTPSConnection(code["h"], code["p"], context=context, timeout=15)
    try:
        conn.connect()
    except OSError as e:
        sys.exit(f"Couldn't reach the home PC at {code['h']}: {e}. Is XVid running there, on the same Wi-Fi?")
    if hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest() != code["f"]:
        sys.exit("The PC at that address isn't the one that made this code. Nothing was sent.")
    body = json.dumps({"code": code["c"], "name": name, "url": f"https://{lan_ip()}:{HTTPS_PORT}"})
    conn.request("POST", "/api/join", body, {"Content-Type": "application/json"})
    res = conn.getresponse()
    data = json.loads(res.read())
    if res.status != 200:
        sys.exit(f"The home PC refused: {data.get('detail', res.reason)}")
    return data


def install_authority(cert: str, key: str) -> None:
    mkcert = shutil.which("mkcert")
    if not mkcert:
        sys.exit("mkcert not found. Install it with:  winget install FiloSottile.mkcert")
    root = Path(subprocess.run([mkcert, "-CAROOT"], check=True, capture_output=True, text=True).stdout.strip())
    root.mkdir(parents=True, exist_ok=True)
    for name in ("rootCA.pem", "rootCA-key.pem"):
        if (root / name).exists():
            shutil.copyfile(root / name, root / f"{name}.before-xvid-join")  # keep this PC's own, just in case
    (root / "rootCA.pem").write_text(cert, newline="")  # byte for byte, as mkcert wrote it
    (root / "rootCA-key.pem").write_text(key, newline="")


parser = argparse.ArgumentParser()
parser.add_argument("--name", required=True)
parser.add_argument("--join")
args = parser.parse_args()

settings = config.load()
settings["name"] = args.name.strip() or settings["name"]
if args.join:
    data = fetch_from_home(decode(args.join), settings["name"])
    install_authority(data["ca_cert"], data["ca_key"])
    TOKEN_FILE.write_text(data["token"])
    settings["home"] = data["home"]
    for pc in data["pcs"]:
        config.upsert_peer(settings, pc["name"], pc["url"])
    print(f"Joined the home PC at {data['home']}.")
config.save(settings)
print(f"This PC is called '{settings['name']}' on the phone.")
