# XVid

A video library shared between your PC and your Android phone.

- Share an X post from the X app → **XVid** → the PC downloads it with yt-dlp.
- Browse and stream the library from the phone (while the PC is on).
- **Save to phone** keeps a copy in your gallery, so you have it even when the PC is off.
- **Upload** sends a video from the phone to the PC library.

```
Phone (XVid installed app) ──Tailscale HTTPS──► tailscale serve ──► 127.0.0.1:8000 FastAPI ──► yt-dlp
                                                                                    └─► library folder
```

## PC setup (Windows, one time)

```powershell
winget install Gyan.FFmpeg          # best quality (merges separate audio/video); optional
winget install Tailscale.Tailscale  # then sign in from the tray icon
```

In the Tailscale admin console (<https://login.tailscale.com/admin/dns>), enable **MagicDNS** and **HTTPS Certificates**. Android only allows "share to app" for HTTPS apps.

Start XVid:

```powershell
.\run.ps1
```

This creates a virtualenv in `%LOCALAPPDATA%\XVid\venv` (outside OneDrive), updates yt-dlp to nightly, and serves on `127.0.0.1:8000`. On the first run it prints a **token**; it is also saved in `token.txt`.

Expose it to your tailnet only (run once; it persists):

```powershell
tailscale serve --bg 8000
tailscale serve status   # shows your URL, e.g. https://my-pc.tail1234.ts.net
```

## Phone setup (Android, one time)

1. Install **Tailscale** from the Play Store and sign in with the same account.
2. In **Chrome**, open the URL from `tailscale serve status` and log in with the token.
3. Chrome menu (⋮) → **Add to Home screen** → **Install**.
4. In the X app: Share → **XVid**. If it's not listed, tap **More** and pin it.

Saved videos go to **Downloads**. Google Photos shows them under *Library → Download*.

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `XVID_LIBRARY` | `~\Videos\XVid` | Library folder |
| `XVID_TOKEN` | contents of `token.txt` | Login token |
| `XVID_COOKIES_BROWSER` | — | e.g. `firefox`: use that browser's X login |
| `XVID_COOKIES_FILE` | — | Path to a Netscape `cookies.txt` |

### Sensitive, protected and subscriber-only posts

yt-dlp needs to be logged in as you. It can only download what **your account** can see, so subscriber-only videos need an active subscription on that account.

- **Easiest:** log in to X in **Firefox**, then `$env:XVID_COOKIES_BROWSER = "firefox"; .\run.ps1`.
  Chrome and Edge cookies can't be read on Windows (app-bound encryption).
- **Or:** export a `cookies.txt` with a browser extension and set `XVID_COOKIES_FILE`.

Cookies are your X session. Keep them private (`cookies.txt` is git-ignored). Heavy automated use can get an account flagged.

## Start automatically with Windows

Task Scheduler → *Create Task* → trigger **At log on** → action:
`powershell.exe -WindowStyle Hidden -ExecutionPolicy Bypass -File "<path>\run.ps1"`.
Set the cookie variables as user environment variables (System → Environment Variables) so the task sees them.

## Troubleshooting

- **A download fails:** yt-dlp updates on every `run.ps1` start. Restart it. X breaks yt-dlp often and fixes land in nightly first.
- **"Can't reach the PC":** the PC is off, the server isn't running, or Tailscale is disconnected on one side.
- **XVid missing from the share menu:** it must be *installed* from Chrome (step 3), opened over the HTTPS `ts.net` URL.
