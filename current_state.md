# XVid: current state

*Last updated: 2026-09-30*

## What it is

Download X (Twitter) videos on an Android phone, anywhere, with the native XVid phone app, and
move them between the phone and your Windows PCs on the home Wi-Fi.

## What works

- **Phone downloads** (Share → XVid → Download to phone, or a pasted link): the phone app runs
  yt-dlp itself, on any network, with no PC. Videos go to `Movies/XVid` (gallery, Google Photos).
  Failures retry when the connection is back, after a yt-dlp update, or after an X login.
- **To PC** from the phone (share sheet or a pasted link) or pasting a link on a PC saves it to that
  PC's **PC library** (`%USERPROFILE%\Videos\XVid`). Links for a PC that isn't reachable wait in the
  phone's **queue** and go in the background once it is. The folder shows only videos; metadata
  lives in its hidden `.xvid` subfolder.
- **Phone app and the PCs** (home Wi-Fi):
  - Pairing by scanning a QR code (**Add a phone**); no certificate to install in Android settings.
  - Each reachable PC's section shows its PC library: stream, **Save to phone**, delete.
  - **Upload** a phone library video, or any gallery video, to a reachable PC.
  - The app finds PCs on the Wi-Fi by id (mDNS), so PC addresses can change.
- **Several PCs, one phone app:** the home PC hands out **Add a PC** codes; other PCs join with them.
- **PC web UI** (the PC's own browser): paste a link, the PC library, upload, Add a phone, Add a PC.
- **Sensitive/protected posts:** the phone app has its own X login; PCs use `cookies.txt` (or Firefox's login).
- **Setup:**
  - `setup.cmd` installs everything, makes the HTTPS certificate, opens the firewall (HTTPS and
    mDNS), and optionally starts XVid with Windows.
  - `start.cmd` and `stop.cmd` start and stop XVid.
- **Releases:** pushing a version tag builds, signs and publishes the phone app to GitHub Releases.

## How it's built

| Part | Where |
|---|---|
| Server (FastAPI + yt-dlp): PC library, downloads, pairing, multi-PC | `app/main.py` |
| Launcher: HTTPS :8443; finding this PC on the network (mDNS) | `app/__main__.py`, `app/discovery.py` |
| Paths, ports, data folder; this PC's id, name and known PCs | `app/network.py`, `app/config.py` |
| PC web UI | `static/app.js`, `static/index.html`, `static/style.css` |
| Windows setup and scripts | `setup.ps1`, `run.ps1`, `tools/common.ps1`, `*.cmd` |
| Certificate creation, join another PC | `tools/setup_https.py`, `tools/configure.py` |
| Phone app: behaviour (plain Kotlin, tested with fakes) | `android/core` |
| Phone app: Android screens, services, notifications | `android/app` |

**Where state lives:**
- Each PC's own state is in `%LOCALAPPDATA%\XVid`: Python environment, token, certificates, `xvid.json`, log. It's outside the repo because the repo may be synced between PCs.
- `cookies.txt` stays in the repo folder. It's git-ignored and never committed.
- The phone app keeps its pairing, PC list, queue and X login in its private storage.

**Security model:**
- mkcert creates a private certificate authority. The phone app trusts it for its own connections only, from the fingerprint in the pairing QR code; joined PCs share that authority and the login token.
- The phone sends its login as a header.
- The PC's own browser is trusted only on XVid's own pages.
- One-time codes expire after 10 minutes, and repeated wrong attempts are blocked for 5 minutes.

## Known limits

- **Moving from the old phone web app:** uninstall it from the phone, install the app and pair again (see the README).
- **No ffmpeg installed yet** on the PCs. Some PC downloads may come in slightly lower quality.
- **Updating a PC:** in its XVid folder, `git pull`, then `setup.cmd` (or `start.cmd` when nothing about setup changed). Each PC updates separately.

## Open items / ideas

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
| `cee129e` | The phone app finds PCs on the network (#10) |
| `46456cc` | PC libraries on the phone (#11) |
| `e554313` | To PC and the queue (#12) |
| `0256dfb` | Upload from the phone (#13) |
| (#14) | The old phone web app is removed |
