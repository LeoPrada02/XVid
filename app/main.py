"""XVid: a video library shared between the PC and the phone.

The PC downloads X videos with yt-dlp into one folder (the library). The phone
browses it over the home Wi-Fi (HTTPS, see tools/setup_https.py), streams
videos, and saves the ones it wants. Downloads started on the phone go straight
to the phone instead (the PC only passes them through).
"""

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import ssl
import threading
import time
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote, urlencode, urlparse

import qrcode
import qrcode.image.svg
import yt_dlp
from fastapi import Depends, FastAPI, Form, HTTPException, Request, Response, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, RedirectResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from yt_dlp.networking import Request as YdlRequest

from app import config
from app.network import CA_FILE, CERT_FILE, HTTP_PORT, HTTPS_PORT, TOKEN_FILE, cert_ip, current_ip, https_ready

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "static"

LIBRARY = Path(os.environ.get("XVID_LIBRARY") or Path.home() / "Videos" / "XVid").expanduser().resolve()
META = LIBRARY / ".xvid"  # thumbnails, video info and temp files, hidden so the library only shows videos
TEMP = META / "tmp"
TEMP.mkdir(parents=True, exist_ok=True)
if os.name == "nt":
    import ctypes
    ctypes.windll.kernel32.SetFileAttributesW(str(META), 0x2)  # FILE_ATTRIBUTE_HIDDEN

# Needed for sensitive, protected or subscriber-only posts your account can see.
COOKIES_BROWSER = os.environ.get("XVID_COOKIES_BROWSER")  # e.g. "firefox"
# Netscape cookies.txt: set by XVID_COOKIES_FILE, or just dropped in XVid's folder.
COOKIES_FILE = os.environ.get("XVID_COOKIES_FILE") or (str(ROOT / "cookies.txt") if (ROOT / "cookies.txt").exists() else None)
HAS_FFMPEG = shutil.which("ffmpeg") is not None

VIDEO_EXTS = {".mp4", ".webm", ".mkv", ".mov", ".m4v"}
THUMB_EXTS = (".jpg", ".webp", ".png")
X_HOSTS = {"x.com", "twitter.com"}

OUTTMPL = "%(uploader_id)s_%(id)s%(playlist_index&_{}|)s.%(ext)s"
# Without ffmpeg, pick a single file that already has audio and video.
FORMAT = "bv*+ba/b" if HAS_FFMPEG else "b[ext=mp4]/b"


def tidy_library() -> None:
    """Move metadata that older versions kept next to the videos into META, and clear leftover temp files."""
    for video in LIBRARY.iterdir():
        if video.is_file() and video.suffix.lower() in VIDEO_EXTS:
            for ext in (".json", *THUMB_EXTS):
                if (old := video.with_suffix(ext)).exists():
                    os.replace(old, META / old.name)
    shutil.rmtree(LIBRARY / ".tmp", ignore_errors=True)
    for leftover in TEMP.iterdir():
        if leftover.is_dir():
            shutil.rmtree(leftover, ignore_errors=True)
        else:
            leftover.unlink(missing_ok=True)


tidy_library()


def load_token() -> str:
    if token := os.environ.get("XVID_TOKEN"):
        return token
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(secrets.token_urlsafe(24))
    return TOKEN_FILE.read_text().strip()


TOKEN = load_token()  # joined PCs share the home PC's token, so one phone login works on all of them
SESSION = hashlib.sha256(f"xvid:{TOKEN}".encode()).hexdigest()
# For <video>/<img>/download links, which can't send headers. Only opens media, not the rest of the API.
MEDIA_KEY = hmac.new(TOKEN.encode(), b"xvid-media", hashlib.sha256).hexdigest()[:32]
COOKIE = "xvid_session"
CONFIG = config.load()

print(f"XVid library: {LIBRARY}", flush=True)
print(f"XVid token:   {TOKEN}", flush=True)

app = FastAPI(title="XVid")
app.mount("/static", StaticFiles(directory=STATIC), name="static")
# The phone app is served by the home PC but also talks to the other PCs. Every request still needs
# the session (sent as a header, never an ambient cookie), so allowing any origin exposes nothing.
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"],
                   allow_headers=["Authorization", "Content-Type"])


def trusted_local(request: Request) -> bool:
    """The PC's own browser is always trusted (it can read token.txt anyway), but only for XVid's own
    pages: a website open in that browser could otherwise make requests to XVid in its name."""
    return is_local(request) and request.headers.get("sec-fetch-site", "none") in ("same-origin", "none")


def has_session(request: Request) -> bool:
    auth = request.headers.get("authorization", "")
    given = auth.removeprefix("Bearer ") if auth.startswith("Bearer ") else request.cookies.get(COOKIE, "")
    return secrets.compare_digest(given.encode(), SESSION.encode())


def require_auth(request: Request) -> None:
    if not (trusted_local(request) or has_session(request)):
        raise HTTPException(401, "Not logged in")


def require_media(request: Request, t: str = "") -> None:
    if not (trusted_local(request) or has_session(request) or secrets.compare_digest(t.encode(), MEDIA_KEY.encode())):
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


def parse_x_url(text: str) -> str:
    if url := normalize_x_url(text) or find_x_url(text):
        return url
    raise HTTPException(400, "That doesn't look like a link to an X post")


def ydl_opts(**extra) -> dict:
    opts = {"quiet": True, "no_warnings": True, "noprogress": True, **extra}
    if COOKIES_BROWSER:
        opts["cookiesfrombrowser"] = (COOKIES_BROWSER,)
    if COOKIES_FILE:
        opts["cookiefile"] = COOKIES_FILE
    return opts


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


def meta_file(video: Path, ext: str) -> Path:
    return META / f"{video.stem}{ext}"


def write_sidecar(video: Path, data: dict) -> None:
    meta_file(video, ".json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def run_job(job_id: str, url: str) -> None:
    def progress(d: dict) -> None:
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            pct = round(100 * d.get("downloaded_bytes", 0) / total) if total else None
            update_job(job_id, status="downloading", progress=pct)

    opts = ydl_opts(
        paths={"home": str(LIBRARY), "temp": str(TEMP), "thumbnail": str(META)},
        outtmpl=OUTTMPL,
        format=FORMAT,
        merge_output_format="mp4",
        writethumbnail=True,
        restrictfilenames=True,
        progress_hooks=[progress],
    )

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
    url = parse_x_url(body.url)
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


# ---------------------------------------------------------------- straight to the phone
# A browser can't run yt-dlp, so the PC finds the video and passes it through to the
# phone as a download. Nothing is kept on the PC or added to the library.

DIRECT_TTL = 1800  # seconds a prepared download stays available ("Save again")
direct_items: dict[str, dict] = {}
direct_lock = threading.Lock()


def progressive_format(entry: dict) -> dict | None:
    """The best single file with both audio and video, which can be streamed through as is."""
    candidates = [f for f in entry.get("formats") or []
                  if f.get("protocol") in ("http", "https") and f.get("vcodec") != "none" and f.get("acodec") != "none"]
    return max(candidates, key=lambda f: (f.get("height") or 0, f.get("tbr") or 0), default=None)


def direct_filename(entry: dict, index: int, count: int) -> str:
    suffix = f"_{index}" if count > 1 else ""
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", f"{entry.get('uploader_id') or 'x'}_{entry.get('id')}{suffix}.mp4")


def expire_direct() -> None:
    now = time.time()
    with direct_lock:
        for item_id in [k for k, item in direct_items.items() if item["expires"] < now]:
            item = direct_items.pop(item_id)
            if item.get("folder"):
                shutil.rmtree(item["folder"], ignore_errors=True)


@app.post("/api/direct", dependencies=[Depends(require_auth)])
def create_direct(body: JobIn) -> list[dict]:
    """Prepare a phone download: returns one item per video in the post."""
    url = parse_x_url(body.url)
    expire_direct()
    folder = None
    try:
        with yt_dlp.YoutubeDL(ydl_opts()) as ydl:
            info = ydl.extract_info(url, download=False)
        entries = [e for e in info.get("entries") or [info] if e]
        formats = [progressive_format(e) for e in entries]
        if all(formats):
            sources = [{"url": f["url"], "headers": f.get("http_headers") or {}} for f in formats]
        else:
            # Some videos only come as separate audio and video: download and merge them in a temp folder.
            folder = TEMP / f"direct-{uuid.uuid4().hex}"
            opts = ydl_opts(paths={"home": str(folder), "temp": str(folder)}, outtmpl=OUTTMPL,
                            format=FORMAT, merge_output_format="mp4")
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=True)
            entries = [e for e in info.get("entries") or [info] if e and e.get("requested_downloads")]
            sources = [{"path": e["requested_downloads"][0]["filepath"]} for e in entries]
    except Exception as e:
        raise HTTPException(502, clean_error(str(e)))
    if not entries:
        raise HTTPException(404, "No video found in this post")

    items = []
    with direct_lock:
        for i, (entry, source) in enumerate(zip(entries, sources), 1):
            item = {"id": secrets.token_urlsafe(12), "title": entry.get("title"),
                    "filename": direct_filename(entry, i, len(entries)),
                    "expires": time.time() + DIRECT_TTL, "folder": folder, **source}
            direct_items[item["id"]] = item
            items.append({"id": item["id"], "title": item["title"], "filename": item["filename"]})
    return items


@app.get("/api/direct/{item_id}", dependencies=[Depends(require_media)])
def direct_download(item_id: str) -> Response:
    with direct_lock:
        item = direct_items.get(item_id)
    if not item or item["expires"] < time.time():
        raise HTTPException(404, "This download expired. Share the post to XVid again.")
    if "path" in item:
        return FileResponse(item["path"], filename=item["filename"])

    ydl = yt_dlp.YoutubeDL(ydl_opts())  # reuses yt-dlp's headers and cookies for X's servers
    try:
        upstream = ydl.urlopen(YdlRequest(item["url"], headers=item["headers"]))
    except Exception as e:
        ydl.close()
        raise HTTPException(502, f"Couldn't get the video from X: {e}")

    def body():
        try:
            while chunk := upstream.read(256 * 1024):
                yield chunk
        finally:
            upstream.close()
            ydl.close()

    headers = {"Content-Disposition": f'attachment; filename="{item["filename"]}"'}
    if length := upstream.headers.get("Content-Length"):
        headers["Content-Length"] = length  # lets Android show download progress
    return StreamingResponse(body(), media_type="video/mp4", headers=headers)


# ---------------------------------------------------------------- library

def library_file(name: str) -> Path:
    path = (LIBRARY / name).resolve()
    if path.parent != LIBRARY or not path.is_file():
        raise HTTPException(404, "Not found")
    return path


def video_entry(path: Path) -> dict:
    meta = {}
    sidecar = meta_file(path, ".json")
    if sidecar.exists():
        try:
            meta = json.loads(sidecar.read_text(encoding="utf-8"))
        except ValueError:
            pass
    thumb = next((meta_file(path, ext).name for ext in THUMB_EXTS if meta_file(path, ext).exists()), None)
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
        meta_file(path, ext).unlink(missing_ok=True)
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


MAX_THUMB = 2 * 1024 * 1024


@app.post("/api/videos/{name}/thumb", dependencies=[Depends(require_auth)])
def set_thumbnail(name: str, file: UploadFile, duration: float | None = Form(None)) -> dict:
    """A thumbnail for a video that came without one (uploads, files dropped into the folder).
    The browser grabs a frame from the video and sends it here, so no ffmpeg is needed."""
    video = library_file(name)
    data = file.file.read(MAX_THUMB + 1)
    if len(data) > MAX_THUMB or not data.startswith(b"\xff\xd8"):  # JPEG only
        raise HTTPException(400, "Thumbnail must be a JPEG under 2 MB")
    for ext in THUMB_EXTS:
        meta_file(video, ext).unlink(missing_ok=True)
    meta_file(video, ".jpg").write_bytes(data)
    sidecar = meta_file(video, ".json")
    meta = json.loads(sidecar.read_text(encoding="utf-8")) if sidecar.exists() else {"title": video.stem}
    if duration and not meta.get("duration"):
        meta["duration"] = duration
        write_sidecar(video, meta)
    return video_entry(video)


@app.get("/media/{name}", dependencies=[Depends(require_media)])
def media(name: str, download: bool = False) -> FileResponse:
    path = library_file(name)
    if path.suffix.lower() not in VIDEO_EXTS:
        raise HTTPException(404, "Not found")
    if download:
        return FileResponse(path, filename=path.name)  # attachment -> "Save to phone"
    return FileResponse(path)  # supports Range requests, so seeking works while streaming


@app.get("/thumb/{name}", dependencies=[Depends(require_media)])
def thumb(name: str) -> FileResponse:
    path = (META / name).resolve()
    if path.parent != META or not path.is_file() or path.suffix.lower() not in THUMB_EXTS:
        raise HTTPException(404, "Not found")
    return FileResponse(path, headers={"Cache-Control": "private, max-age=604800"})


# ---------------------------------------------------------------- session & PWA

class LoginIn(BaseModel):
    token: str


LOGIN_WINDOW = 300  # seconds
LOGIN_MAX_FAILURES = 5
login_failures: dict[str, list[float]] = {}


def guard_attempts(request: Request) -> str:
    """Block a device after too many wrong tokens or pairing codes. Returns its IP."""
    ip = request.client.host if request.client else "?"
    now = time.time()
    login_failures[ip] = [t for t in login_failures.get(ip, []) if now - t < LOGIN_WINDOW]
    if len(login_failures[ip]) >= LOGIN_MAX_FAILURES:
        raise HTTPException(429, "Too many wrong attempts, wait a few minutes")
    return ip


def start_session(ip: str, request: Request, response: Response) -> dict:
    login_failures.pop(ip, None)
    response.set_cookie(COOKIE, SESSION, max_age=365 * 86400, httponly=True, samesite="lax",
                        secure=request.url.scheme == "https")
    # The app keeps these to talk to every PC (they're the same on all joined PCs).
    return {"ok": True, "session": SESSION, "media_key": MEDIA_KEY}


@app.post("/api/login")
def login(body: LoginIn, request: Request, response: Response) -> dict:
    ip = guard_attempts(request)
    if not secrets.compare_digest(body.token.strip().encode(), TOKEN.encode()):
        login_failures[ip].append(time.time())
        raise HTTPException(401, "Wrong token")
    return start_session(ip, request, response)


# Pairing: the PC shows a QR code with a one-time code, so the phone logs in without typing the token.
PAIR_TTL = 600  # seconds
pair_codes: dict[str, float] = {}  # code -> expiry time


def new_code(codes: dict[str, float]) -> str:
    now = time.time()
    for code in [c for c, expiry in codes.items() if expiry < now]:
        del codes[code]
    code = secrets.token_urlsafe(16)
    codes[code] = now + PAIR_TTL
    return code


def home_url() -> str:
    return CONFIG["home"] or config.self_url()


@app.post("/api/pair", dependencies=[Depends(require_auth)])
def create_pairing(request: Request) -> dict:
    if not trusted_local(request):
        raise HTTPException(403, "Only available on the PC")
    if not https_ready():
        raise HTTPException(409, "Phone access isn't set up yet. Run setup.cmd on the PC.")
    if not CA_FILE.exists():  # set up by an older version, before the app pinned the CA
        raise HTTPException(409, "Phone access needs updating for the XVid app. Run setup.cmd on the PC again.")
    code = new_code(pair_codes)
    # The code goes in the #fragment, which browsers never send over the (plain HTTP) network.
    # The phone installs the web app from the home PC, and logs in on this PC. (The native app comes
    # from GitHub Releases instead: see app_release.)
    fragment = urlencode({"pair": code, "home": home_url(), "pc": config.self_url()})
    url = f"http://{cert_ip()}:{HTTP_PORT}/setup#{fragment}"
    # The phone app scans this one instead. With the CA's fingerprint it checks it's really talking to
    # this PC (see pairing_ca) before it sends the code, and trusts the CA for its own connections only.
    app_text = "xvid://pair?" + urlencode({"pc": config.self_url(), "code": code, "fp": fingerprint(CA_FILE)})
    return {"url": url, "svg": qr_svg(url), "expires_in": PAIR_TTL,
            "app": {"text": app_text, "svg": qr_svg(app_text)}}


@app.get("/api/pair/ca")
def pairing_ca() -> Response:
    """The public certificate of the CA that signs every joined PC's certificate. The phone app fetches it
    while pairing, before it trusts this PC, and only uses it if it matches the fingerprint in the QR code."""
    if not CA_FILE.exists():
        raise HTTPException(404, "Phone access isn't set up yet. Run setup.cmd on the PC.")
    return Response(CA_FILE.read_text(), media_type="application/x-pem-file")


def fingerprint(pem_file: Path) -> str:
    """SHA-256 of a PEM certificate's DER bytes, in hex: what a QR code or join code pins."""
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem_file.read_text())).hexdigest()


# The release workflow (.github/workflows/release.yml) attaches one APK per CPU type; this one fits most phones.
PHONE_APK = "XVid-arm64-v8a.apk"


@app.get("/api/app-release", dependencies=[Depends(require_auth)])
def app_release(request: Request) -> dict:
    """Step 1 of Add a phone: where to get the phone app (the latest GitHub Release), or null if unknown."""
    if not trusted_local(request):
        raise HTTPException(403, "Only available on the PC")
    repo = config.releases_repo()
    if repo is None:
        return {"install": None}
    page = f"https://github.com/{repo}/releases/latest"
    url = f"{page}/download/{PHONE_APK}"
    return {"install": {"url": url, "page": page, "svg": qr_svg(url)}}


def qr_svg(text: str) -> str:
    qr = qrcode.make(text, image_factory=qrcode.image.svg.SvgPathImage, border=2)
    return qr.to_string(encoding="unicode")


class PairIn(BaseModel):
    code: str


@app.post("/api/pair/redeem")
def redeem_pairing(body: PairIn, request: Request, response: Response) -> dict:
    ip = guard_attempts(request)
    expiry = pair_codes.pop(body.code, None)
    if expiry is None or expiry < time.time():
        login_failures[ip].append(time.time())
        raise HTTPException(401, "This pairing code expired or was already used. Scan a new QR code on the PC.")
    return start_session(ip, request, response)


@app.get("/api/ping")
def ping() -> dict:
    """Unauthenticated; the phone setup page uses it to detect when the certificate is trusted."""
    return {"ok": True}


@app.get("/api/me", dependencies=[Depends(require_auth)])
def me(request: Request) -> dict:
    return {"ok": True, "cookies": bool(COOKIES_BROWSER or COOKIES_FILE), "ffmpeg": HAS_FFMPEG,
            "library": str(LIBRARY), "local": trusted_local(request), "phone_ready": https_ready(),
            "id": CONFIG["id"], "name": CONFIG["name"], "url": config.self_url(), "is_home": not CONFIG["home"],
            "session": SESSION, "media_key": MEDIA_KEY}


# ---------------------------------------------------------------- several PCs
# The home PC hands out "Add a PC" codes. A PC that joins with one gets the home's certificate
# authority and token (see tools/configure.py), so the phone's single app trusts and logs in to both.

join_codes: dict[str, float] = {}


def known_pcs() -> list[dict]:
    this = {"id": CONFIG["id"], "name": CONFIG["name"], "url": config.self_url(), "home": not CONFIG["home"]}
    return [this] + [{**p, "home": p["url"] == CONFIG["home"]} for p in CONFIG["peers"]]


@app.get("/api/pcs", dependencies=[Depends(require_auth)])
def list_pcs() -> list[dict]:
    return known_pcs()


@app.post("/api/pc-code", dependencies=[Depends(require_auth)])
def create_join_code(request: Request) -> dict:
    """A code to paste into setup.cmd on another PC. It pins this PC's certificate, so the joining PC
    can check it's really talking to this PC before it receives the certificate authority."""
    if not trusted_local(request):
        raise HTTPException(403, "Only available on the PC")
    if CONFIG["home"]:
        raise HTTPException(409, "Add PCs from the home PC")
    if not https_ready():
        raise HTTPException(409, "Phone access isn't set up yet. Run setup.cmd on this PC first.")
    payload = {"h": current_ip(), "p": HTTPS_PORT, "c": new_code(join_codes), "f": fingerprint(CERT_FILE)}
    code = "XVID-" + base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    return {"code": code, "expires_in": PAIR_TTL}


class JoinIn(BaseModel):
    code: str
    name: str
    url: str
    id: str | None = None  # None from PCs set up before PCs had ids


@app.post("/api/join")
def join(body: JoinIn, request: Request) -> dict:
    ip = guard_attempts(request)
    expiry = join_codes.pop(body.code, None)
    if CONFIG["home"] or expiry is None or expiry < time.time():
        login_failures[ip].append(time.time())
        raise HTTPException(401, "This code expired or was already used. Create a new one with 'Add a PC'.")
    root = config.caroot()
    config.upsert_peer(CONFIG, body.name, body.url, body.id)
    config.save(CONFIG)
    return {"token": TOKEN, "home": config.self_url(), "pcs": known_pcs(),
            "ca_cert": (root / "rootCA.pem").read_text(), "ca_key": (root / "rootCA-key.pem").read_text()}


class AnnounceIn(BaseModel):
    name: str
    url: str
    id: str | None = None


@app.post("/api/pcs/announce", dependencies=[Depends(require_auth)])
def announce(body: AnnounceIn) -> list[dict]:
    """A joined PC reports its current name and address (they can change); returns every PC."""
    config.upsert_peer(CONFIG, body.name, body.url, body.id)
    config.save(CONFIG)
    return known_pcs()


def announce_to_home() -> None:
    """On a joined PC: keep the home PC up to date with this PC's address, and learn about other PCs."""
    context = ssl.create_default_context(cafile=str(CA_FILE))
    # Any certificate the shared authority signed is the home PC's, even one made for its old address.
    context.check_hostname = False
    while True:
        body = json.dumps({"id": CONFIG["id"], "name": CONFIG["name"], "url": config.self_url()}).encode()
        try:
            req = urllib.request.Request(f"{CONFIG['home']}/api/pcs/announce", data=body, method="POST", headers={
                "Content-Type": "application/json", "Authorization": f"Bearer {SESSION}"})
            with urllib.request.urlopen(req, context=context, timeout=10) as res:
                for pc in json.load(res):
                    config.upsert_peer(CONFIG, pc["name"], pc["url"], pc.get("id"))
            config.save(CONFIG)
            time.sleep(600)
        except Exception:
            time.sleep(60)  # the home PC is probably off; try again later


if CONFIG["home"] and https_ready():
    threading.Thread(target=announce_to_home, daemon=True).start()


PHONE_VIEW = os.environ.get("XVID_PHONE_VIEW") == "1"  # for development: treat every browser as a phone


def is_local(request: Request) -> bool:
    """True when the browser runs on this PC (it connects from the same address it connects to)."""
    if PHONE_VIEW or not request.client:
        return False
    server_host = (request.scope.get("server") or (None,))[0]
    return request.client.host in ("127.0.0.1", "::1") or request.client.host == server_host


@app.post("/api/open-folder", dependencies=[Depends(require_auth)])
def open_folder(request: Request) -> dict:
    if not trusted_local(request):
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
    if not CA_FILE.exists():
        raise HTTPException(404, "Phone access isn't set up yet. Run setup.cmd on the PC.")
    return FileResponse(CA_FILE, media_type="application/x-x509-ca-cert", filename="xvid-ca.crt")


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})


@app.get("/manifest.webmanifest")
def manifest() -> FileResponse:
    return FileResponse(STATIC / "manifest.webmanifest", media_type="application/manifest+json")


@app.get("/sw.js")
def service_worker() -> FileResponse:
    return FileResponse(STATIC / "sw.js", media_type="text/javascript", headers={"Cache-Control": "no-cache"})
