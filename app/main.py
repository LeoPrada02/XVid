"""XVid: a video library shared between the PC and the phone.

The PC downloads X videos with yt-dlp into one folder (the library). The phone
browses it over the home Wi-Fi (HTTPS, see tools/setup_https.py), streams
videos, and saves the ones it wants.
"""

import hashlib
import json
import os
import re
import secrets
import shutil
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote, urlparse

import yt_dlp
from fastapi import Depends, FastAPI, HTTPException, Request, Response, UploadFile
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "static"
CA_CERT = ROOT / "certs" / "xvid-ca.crt"  # public root certificate the phone installs to trust XVid

LIBRARY = Path(os.environ.get("XVID_LIBRARY") or Path.home() / "Videos" / "XVid").expanduser().resolve()
TEMP = LIBRARY / ".tmp"
TEMP.mkdir(parents=True, exist_ok=True)

# Needed for sensitive, protected or subscriber-only posts your account can see.
COOKIES_BROWSER = os.environ.get("XVID_COOKIES_BROWSER")  # e.g. "firefox"
COOKIES_FILE = os.environ.get("XVID_COOKIES_FILE")  # Netscape cookies.txt
HAS_FFMPEG = shutil.which("ffmpeg") is not None

VIDEO_EXTS = {".mp4", ".webm", ".mkv", ".mov", ".m4v"}
THUMB_EXTS = (".jpg", ".webp", ".png")
X_HOSTS = {"x.com", "twitter.com"}


def load_token() -> str:
    if token := os.environ.get("XVID_TOKEN"):
        return token
    token_file = ROOT / "token.txt"
    if not token_file.exists():
        token_file.write_text(secrets.token_urlsafe(24))
    return token_file.read_text().strip()


TOKEN = load_token()
SESSION = hashlib.sha256(f"xvid:{TOKEN}".encode()).hexdigest()
COOKIE = "xvid_session"

print(f"XVid library: {LIBRARY}", flush=True)
print(f"XVid token:   {TOKEN}", flush=True)

app = FastAPI(title="XVid")
app.mount("/static", StaticFiles(directory=STATIC), name="static")


def require_auth(request: Request) -> None:
    if not secrets.compare_digest(request.cookies.get(COOKIE, "").encode(), SESSION.encode()):
        raise HTTPException(401, "Not logged in")


# ---------------------------------------------------------------- X links

def normalize_x_url(raw: str) -> str | None:
    """Return a canonical https://x.com/.../status/<id> URL, or None if it isn't an X post."""
    try:
        u = urlparse(raw.strip())
    except ValueError:
        return None
    host = (u.hostname or "").lower().removeprefix("www.").removeprefix("mobile.")
    if u.scheme not in ("http", "https") or host not in X_HOSTS:
        return None
    if not re.search(r"/status/\d+", u.path):
        return None
    return f"https://x.com{u.path}"  # drops tracking params like ?s=20&t=...


def find_x_url(text: str) -> str | None:
    for candidate in re.findall(r"https?://\S+", text):
        if url := normalize_x_url(candidate):
            return url
    return None


# ---------------------------------------------------------------- download jobs

jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()
executor = ThreadPoolExecutor(max_workers=2)


def update_job(job_id: str, **fields) -> None:
    with jobs_lock:
        jobs[job_id].update(fields)


def clean_error(message: str) -> str:
    message = re.sub(r"^ERROR:\s*(\[\w+\]\s*[\w-]+:\s*)?", "", message.strip())
    if not (COOKIES_BROWSER or COOKIES_FILE):
        message += " (If the post is sensitive, protected or subscriber-only, configure cookies: see README.)"
    return message


def write_sidecar(video: Path, data: dict) -> None:
    video.with_suffix(".json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def run_job(job_id: str, url: str) -> None:
    def progress(d: dict) -> None:
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            pct = round(100 * d.get("downloaded_bytes", 0) / total) if total else None
            update_job(job_id, status="downloading", progress=pct)

    opts = {
        "paths": {"home": str(LIBRARY), "temp": str(TEMP)},
        "outtmpl": "%(uploader_id)s_%(id)s%(playlist_index&_{}|)s.%(ext)s",
        # Without ffmpeg, pick a single file that already has audio and video.
        "format": "bv*+ba/b" if HAS_FFMPEG else "b[ext=mp4]/b",
        "merge_output_format": "mp4",
        "writethumbnail": True,
        "restrictfilenames": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "progress_hooks": [progress],
    }
    if COOKIES_BROWSER:
        opts["cookiesfrombrowser"] = (COOKIES_BROWSER,)
    if COOKIES_FILE:
        opts["cookiefile"] = COOKIES_FILE

    update_job(job_id, status="downloading")
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
        files = []
        for entry in info.get("entries") or [info]:
            if not entry or not entry.get("requested_downloads"):
                continue
            video = Path(entry["requested_downloads"][0]["filepath"])
            write_sidecar(video, {
                "title": entry.get("title"),
                "uploader": entry.get("uploader"),
                "uploader_id": entry.get("uploader_id"),
                "source_url": url,
                "duration": entry.get("duration"),
            })
            files.append(video.name)
        if not files:
            raise RuntimeError("No video found in this post")
        update_job(job_id, status="done", progress=100, files=files, title=info.get("title"))
    except Exception as e:
        update_job(job_id, status="error", error=clean_error(str(e)))


class JobIn(BaseModel):
    url: str


@app.post("/api/jobs", dependencies=[Depends(require_auth)])
def create_job(body: JobIn) -> dict:
    url = normalize_x_url(body.url) or find_x_url(body.url)
    if not url:
        raise HTTPException(400, "That doesn't look like a link to an X post")
    with jobs_lock:
        for job in jobs.values():
            if job["url"] == url and job["status"] in ("queued", "downloading"):
                return job
        job = {"id": uuid.uuid4().hex[:12], "url": url, "status": "queued", "progress": None,
               "error": None, "files": [], "title": None, "created": time.time()}
        jobs[job["id"]] = job
    executor.submit(run_job, job["id"], url)
    return job


@app.get("/api/jobs", dependencies=[Depends(require_auth)])
def list_jobs() -> list[dict]:
    with jobs_lock:
        return sorted((dict(j) for j in jobs.values()), key=lambda j: j["created"], reverse=True)[:20]


@app.delete("/api/jobs", dependencies=[Depends(require_auth)])
def clear_finished_jobs() -> dict:
    with jobs_lock:
        for job_id in [k for k, j in jobs.items() if j["status"] in ("done", "error")]:
            del jobs[job_id]
    return {"ok": True}


# ---------------------------------------------------------------- library

def library_file(name: str) -> Path:
    path = (LIBRARY / name).resolve()
    if path.parent != LIBRARY or not path.is_file():
        raise HTTPException(404, "Not found")
    return path


def video_entry(path: Path) -> dict:
    meta = {}
    sidecar = path.with_suffix(".json")
    if sidecar.exists():
        try:
            meta = json.loads(sidecar.read_text(encoding="utf-8"))
        except ValueError:
            pass
    thumb = next((path.with_suffix(ext).name for ext in THUMB_EXTS if path.with_suffix(ext).exists()), None)
    stat = path.stat()
    return {
        "name": path.name,
        "title": meta.get("title") or path.stem,
        "uploader": meta.get("uploader"),
        "uploader_id": meta.get("uploader_id"),
        "source_url": meta.get("source_url"),
        "duration": meta.get("duration"),
        "size": stat.st_size,
        "added": stat.st_mtime,
        "thumb": thumb,
    }


@app.get("/api/videos", dependencies=[Depends(require_auth)])
def list_videos() -> list[dict]:
    videos = [video_entry(p) for p in LIBRARY.iterdir() if p.is_file() and p.suffix.lower() in VIDEO_EXTS]
    return sorted(videos, key=lambda v: v["added"], reverse=True)


@app.delete("/api/videos/{name}", dependencies=[Depends(require_auth)])
def delete_video(name: str) -> dict:
    path = library_file(name)
    for ext in (".json", *THUMB_EXTS):
        path.with_suffix(ext).unlink(missing_ok=True)
    path.unlink()
    return {"ok": True}


@app.post("/api/upload", dependencies=[Depends(require_auth)])
def upload_video(file: UploadFile) -> dict:
    original = Path(file.filename or "video.mp4")
    ext = original.suffix.lower()
    if ext not in VIDEO_EXTS:
        raise HTTPException(400, f"Unsupported file type: {ext or 'none'}")
    stem = re.sub(r"[^\w.-]+", "_", original.stem).strip("._") or "video"
    dest = LIBRARY / f"{stem}{ext}"
    n = 1
    while dest.exists():
        dest = LIBRARY / f"{stem}_{n}{ext}"
        n += 1
    tmp = TEMP / f"upload_{uuid.uuid4().hex}{ext}"
    with tmp.open("wb") as out:
        shutil.copyfileobj(file.file, out, 1024 * 1024)
    os.replace(tmp, dest)
    write_sidecar(dest, {"title": original.stem, "uploader": "Uploaded"})
    return video_entry(dest)


@app.get("/media/{name}", dependencies=[Depends(require_auth)])
def media(name: str, download: bool = False) -> FileResponse:
    path = library_file(name)
    if path.suffix.lower() not in VIDEO_EXTS:
        raise HTTPException(404, "Not found")
    if download:
        return FileResponse(path, filename=path.name)  # attachment -> "Save to phone"
    return FileResponse(path)  # supports Range requests, so seeking works while streaming


@app.get("/thumb/{name}", dependencies=[Depends(require_auth)])
def thumb(name: str) -> FileResponse:
    path = library_file(name)
    if path.suffix.lower() not in THUMB_EXTS:
        raise HTTPException(404, "Not found")
    return FileResponse(path, headers={"Cache-Control": "private, max-age=604800"})


# ---------------------------------------------------------------- session & PWA

class LoginIn(BaseModel):
    token: str


LOGIN_WINDOW = 300  # seconds
LOGIN_MAX_FAILURES = 5
login_failures: dict[str, list[float]] = {}


@app.post("/api/login")
def login(body: LoginIn, request: Request, response: Response) -> dict:
    ip = request.client.host if request.client else "?"
    now = time.time()
    recent = [t for t in login_failures.get(ip, []) if now - t < LOGIN_WINDOW]
    if len(recent) >= LOGIN_MAX_FAILURES:
        raise HTTPException(429, "Too many wrong tokens, wait a few minutes")
    if not secrets.compare_digest(body.token.strip().encode(), TOKEN.encode()):
        login_failures[ip] = [*recent, now]
        raise HTTPException(401, "Wrong token")
    login_failures.pop(ip, None)
    response.set_cookie(COOKIE, SESSION, max_age=365 * 86400, httponly=True, samesite="lax",
                        secure=request.url.scheme == "https")
    return {"ok": True}


@app.get("/api/me", dependencies=[Depends(require_auth)])
def me(request: Request) -> dict:
    return {"ok": True, "cookies": bool(COOKIES_BROWSER or COOKIES_FILE), "ffmpeg": HAS_FFMPEG,
            "library": str(LIBRARY), "local": is_local(request)}


def is_local(request: Request) -> bool:
    """True when the browser runs on this PC (it connects from the same address it connects to)."""
    if not request.client:
        return False
    server_host = (request.scope.get("server") or (None,))[0]
    return request.client.host in ("127.0.0.1", "::1") or request.client.host == server_host


@app.post("/api/open-folder", dependencies=[Depends(require_auth)])
def open_folder(request: Request) -> dict:
    if not is_local(request):
        raise HTTPException(403, "Only available on the PC")
    os.startfile(LIBRARY)  # Windows: opens File Explorer
    return {"ok": True}


@app.get("/share")
def share_target(title: str = "", text: str = "", url: str = "") -> RedirectResponse:
    """Android share-sheet entry point (see share_target in the manifest)."""
    found = find_x_url(f"{url} {text} {title}") or ""
    return RedirectResponse(f"/?share={quote(found)}", status_code=303)


@app.get("/ca.crt")
def ca_certificate() -> FileResponse:
    """The public root certificate, so the phone can trust XVid's HTTPS. Never the private key."""
    if not CA_CERT.exists():
        raise HTTPException(404, "HTTPS not set up: run tools/setup_https.py")
    return FileResponse(CA_CERT, media_type="application/x-x509-ca-cert", filename="xvid-ca.crt")


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})


@app.get("/manifest.webmanifest")
def manifest() -> FileResponse:
    return FileResponse(STATIC / "manifest.webmanifest", media_type="application/manifest+json")


@app.get("/sw.js")
def service_worker() -> FileResponse:
    return FileResponse(STATIC / "sw.js", media_type="text/javascript", headers={"Cache-Control": "no-cache"})
