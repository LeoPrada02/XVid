# XVid: current state

*Last updated: 2026-09-29*

## What it is

Download X (Twitter) videos from the phone's **Share** menu or from a PC, and use them across
your PCs and your Android phone, all on the home Wi-Fi. Windows PCs + Android (Chrome) only.

## What works

- **Share → XVid on the phone** downloads the video straight to the phone (Downloads/gallery).
  A PC looks it up and streams it through; nothing is kept on the PC.
- **To PC** (from the phone) or pasting a link on a PC saves it to that PC's **library**
  (`%USERPROFILE%\Videos\XVid`). The folder shows only videos; metadata lives in its hidden `.xvid` subfolder.
- **Phone app:**
  - Browse, stream and **Save to phone** from every PC that's on.
  - One collapsible section per PC.
  - Upload a video from the phone to a PC.
  - Thumbnails for uploads and hand-copied files are made by the browser from a frame of the video.
- **Several PCs, one phone app:**
  - The home PC hands out **Add a PC** codes; other PCs join with them. Tested with two PCs.
  - The app opens even when the home PC is off, and uses whichever PCs are on.
- **Sensitive/protected posts:** work when `cookies.txt` (your X login) is in the XVid folder.
- **Setup:**
  - `setup.cmd` installs everything, makes the HTTPS certificate, opens the firewall, and optionally starts XVid with Windows.
  - Phones pair by scanning a QR code (**Add a phone**).
  - `start.cmd` and `stop.cmd` start and stop XVid.

## How it's built

| Part | Where |
|---|---|
| Server (FastAPI + yt-dlp): library, downloads, pairing, multi-PC | `app/main.py` |
| Launcher: HTTPS :8443 for the app, HTTP :8000 for phone setup only | `app/__main__.py`, `app/setup_app.py` |
| Paths, ports, data folder; this PC's name and known PCs | `app/network.py`, `app/config.py` |
| Web app (also the installed phone app) and its offline copy | `static/app.js`, `static/index.html`, `static/sw.js` |
| Phone setup page (trust certificate, then log in) | `static/setup.html` |
| Windows setup and scripts | `setup.ps1`, `run.ps1`, `tools/common.ps1`, `*.cmd` |
| Certificate creation, join another PC | `tools/setup_https.py`, `tools/configure.py` |

**Where state lives:**
- Each PC's own state is in `%LOCALAPPDATA%\XVid`: Python environment, token, certificates, `xvid.json`, log. It's outside the repo because the repo may be synced between PCs.
- `cookies.txt` stays in the repo folder. It's git-ignored and never committed.

**Security model:**
- mkcert creates a private certificate authority. The phone installs its public certificate once, and joined PCs share that authority and the login token.
- The phone sends its login as a header. Video and thumbnail links carry a separate key that only opens media.
- The PC's own browser is trusted only on XVid's own pages.
- One-time codes expire after 10 minutes, and repeated wrong attempts are blocked for 5 minutes.

## Known limits

- **Phone downloads need a PC that's on.** A browser can't run yt-dlp.
- **Addresses must stay the same.** Reserve both PCs' IPs in the router. If one changes, run `setup.cmd` again there.
- **Phone setup needs one manual step:** installing the certificate in Android settings.
- **No ffmpeg installed yet.** Some videos may come in slightly lower quality.
- **Updating a PC:** in its XVid folder, `git pull`, then `start.cmd`. Each PC updates separately.

## Open items / ideas

- **Native Android app** (Kotlin + youtubedl-android): phone downloads with no PC, no certificate step, finds PCs automatically. The alternative is to use **Seal** for PC-free downloads.
- **Install ffmpeg** on both PCs for the best quality.

## History

| Commit | Change |
|---|---|
| `4cfe575` | First version: PC library + phone web app (Tailscale) |
| `e79304c` | Home Wi-Fi with HTTPS instead of Tailscale |
| `533cf7a` | One-command setup and QR phone pairing |
| `23ec2d5` | Phone downloads go straight to the phone; tidy library; mobile layout |
| `7843be4` | One phone app for several PCs |
| `26e3046` | Delete boceto.md (from GitHub) |
| `cedcffb` | Collapsible libraries on the phone; thumbnails for uploads |
