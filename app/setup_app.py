"""Plain-HTTP helper on the home network, for phones that don't trust XVid's certificate yet.

It serves only public things: the phone setup page (what the pairing QR code opens) and the
public root certificate. Everything else redirects to the HTTPS app.
"""

from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles

from app.network import CA_FILE, HTTPS_PORT

STATIC = Path(__file__).resolve().parent.parent / "static"

setup_app = FastAPI(title="XVid setup", docs_url=None, redoc_url=None, openapi_url=None)
setup_app.mount("/static", StaticFiles(directory=STATIC), name="static")


@setup_app.get("/setup")
def setup_page() -> FileResponse:
    return FileResponse(STATIC / "setup.html", headers={"Cache-Control": "no-cache"})


@setup_app.get("/ca.crt")
def ca_certificate() -> FileResponse:
    if not CA_FILE.exists():
        raise HTTPException(404, "Phone access isn't set up yet. Run setup.cmd on the PC.")
    return FileResponse(CA_FILE, media_type="application/x-x509-ca-cert", filename="xvid-ca.crt")


@setup_app.get("/{path:path}")
def to_https(path: str, request: Request) -> RedirectResponse:
    return RedirectResponse(f"https://{request.url.hostname}:{HTTPS_PORT}/{path}")
