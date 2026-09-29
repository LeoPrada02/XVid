# XVid

A video library shared between your PC and your Android phone.

- Share an X post from the X app → **XVid** → the PC downloads it with yt-dlp → tap **Save to phone**.
- Browse and stream the library from the phone (same Wi-Fi as the PC, PC on).
- **Save to phone** keeps a copy in your gallery, so you have it even when the PC is off or you're away.
- **Upload** sends a video from the phone to the PC library.

```
Phone (XVid installed app) ──home Wi-Fi, HTTPS──► PC :8443 FastAPI ──► yt-dlp ──► library folder
```

Everything stays on your home network. Nothing is reachable from the internet.

## PC setup (Windows, one time)

```powershell
winget install Gyan.FFmpeg           # best quality (merges separate audio/video); optional
winget install FiloSottile.mkcert    # makes the HTTPS certificate; open a NEW terminal after
python tools\setup_https.py
```

Android only lets XVid appear in the **Share** menu if it's served over HTTPS the phone trusts.
`setup_https.py` creates a private certificate authority on this PC (Windows asks you to confirm)
and a certificate for the PC's Wi-Fi address, saved in `certs/` (git-ignored).
It then prints the remaining steps:

- **Firewall** (admin PowerShell, once), and make sure your Wi-Fi is a *Private* network:
  ```powershell
  New-NetFirewallRule -DisplayName XVid -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow -Profile Private
  ```
- **Fixed address:** in your router, reserve the PC's IP (DHCP reservation). The phone app is tied to
  that address; if it changes, `run.ps1` warns you and you'd need to rerun the setup and reinstall on the phone.

Start XVid:

```powershell
.\run.ps1
```

This creates a virtualenv in `%LOCALAPPDATA%\XVid\venv` (outside OneDrive), updates yt-dlp to nightly,
and prints the addresses to use: `https://localhost:8443` on the PC and `https://<PC IP>:8443` on the phone.
On the first run it creates a **token** in `token.txt`; you log in with it once per device.
Without `certs/`, it runs on `http://127.0.0.1:8000`, reachable from this PC only.

## Phone setup (Android, one time, on the home Wi-Fi)

1. **Trust the certificate.** In Chrome open `https://<PC IP>:8443/ca.crt`. It warns the first time:
   *Advanced → Proceed*. Then *Settings → Security & privacy → More security settings →
   Encryption & credentials → Install a certificate → CA certificate* → pick `xvid-ca.crt`.
   (Menu names vary a bit by brand; search Settings for "CA certificate".)
2. Open `https://<PC IP>:8443` in **Chrome** and log in with the token.
3. Chrome menu (⋮) → **Add to Home screen** → **Install**.
4. In the X app: Share → **XVid**. If it's not listed, tap **More** and pin it.

Saved videos go to **Downloads**. Google Photos shows them under *Library → Download*.

The certificate only makes your phone trust sites signed by *your PC's* mkcert authority. Its private
key stays in mkcert's folder on the PC (`mkcert -CAROOT`); don't share that folder.
To undo: remove the certificate on the phone (*Encryption & credentials → User credentials*)
and run `mkcert -uninstall` on the PC.

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
- **"Can't reach the PC":** the PC is off, the server isn't running, the phone isn't on the home Wi-Fi,
  the firewall rule is missing, or the PC's address changed (see `run.ps1`'s warning).
- **Chrome shows a certificate warning on the app page:** the CA certificate isn't installed on the phone (phone step 1).
- **XVid missing from the share menu:** it must be *installed* from Chrome (phone step 3), opened over `https://`.
