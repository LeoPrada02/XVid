"""Start XVid: `python -m app` (run.ps1 does this).

With certificates (from setup.cmd) it serves the phone over the home Wi-Fi:
  - HTTPS on :8443, the app itself
  - HTTP on :8000, only the phone setup page and the certificate (see setup_app.py)
Without them, only this PC can connect, on http://127.0.0.1:8000.

`--log FILE` sends all output to FILE (run.ps1 -Background uses it at Windows startup).
"""

import sys
import threading

import uvicorn

from app import config, discovery
from app.network import CERT_FILE, HTTP_PORT, HTTPS_PORT, KEY_FILE, cert_ip, https_ready, lan_ip

if "--log" in sys.argv:
    log = open(sys.argv[sys.argv.index("--log") + 1], "w", buffering=1, encoding="utf-8")
    sys.stdout = sys.stderr = log

if https_ready():
    current, expected = lan_ip(), cert_ip()
    if expected and current != expected:
        print(f"Note: this PC's Wi-Fi address changed from {expected} to {current}.")
        print("      The phone app finds it by itself. XVid in a phone's browser needs setup.cmd again.")
    print(f"XVid on this PC: https://localhost:{HTTPS_PORT}  (use 'Add a phone' there)", flush=True)

    settings = config.load()
    discovery.start(settings["id"], settings["name"], HTTPS_PORT)  # so the phone app finds this PC at any address

    setup_server = uvicorn.Server(uvicorn.Config(
        "app.setup_app:setup_app", host="0.0.0.0", port=HTTP_PORT, log_level="warning"))
    threading.Thread(target=setup_server.run, daemon=True).start()

    uvicorn.run("app.main:app", host="0.0.0.0", port=HTTPS_PORT,
                ssl_certfile=str(CERT_FILE), ssl_keyfile=str(KEY_FILE))
else:
    print("Phone access isn't set up, so only this PC can connect. Run setup.cmd to set it up.")
    print(f"XVid on this PC: http://127.0.0.1:{HTTP_PORT}", flush=True)
    uvicorn.run("app.main:app", host="127.0.0.1", port=HTTP_PORT)
