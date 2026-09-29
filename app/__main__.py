"""Start XVid: `python -m app`.

With certificates (tools/setup_https.py) it serves HTTPS to the whole home
network, so the phone can connect. Without them, only this PC can connect.
"""

import uvicorn

from app.network import CERT_FILE, HTTP_PORT, HTTPS_PORT, KEY_FILE, cert_ip, https_ready, lan_ip

if https_ready():
    current, expected = lan_ip(), cert_ip()
    if expected and current != expected:
        print(f"WARNING: this PC's Wi-Fi address changed from {expected} to {current}.")
        print("         The phone app points to the old one. Reserve the address in your router,")
        print("         or run tools/setup_https.py again and reinstall the app on the phone.")
    print(f"XVid on this PC:   https://localhost:{HTTPS_PORT}")
    print(f"XVid on the phone: https://{expected or current}:{HTTPS_PORT}  (same Wi-Fi)", flush=True)
    uvicorn.run("app.main:app", host="0.0.0.0", port=HTTPS_PORT,
                ssl_certfile=str(CERT_FILE), ssl_keyfile=str(KEY_FILE))
else:
    print("HTTPS isn't set up, so only this PC can connect. For the phone, run: python tools/setup_https.py")
    print(f"XVid on this PC: http://127.0.0.1:{HTTP_PORT}", flush=True)
    uvicorn.run("app.main:app", host="127.0.0.1", port=HTTP_PORT)
