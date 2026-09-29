# XVid

Download X (Twitter) videos to your PC and use them from your Android phone.

- In the X app: **Share → XVid** and the video lands in your phone's Downloads (and gallery).
  The PC fetches it for the phone but keeps nothing.
- Links pasted on the PC (or sent with **To PC** from the phone) go to the PC library.
- Browse and watch the PC library from the phone, and **Save to phone** any of it.
- Saved videos stay on your phone, even when the PC is off or you're away.
- **Upload** sends a video from the phone to the PC library.

Everything stays on your home Wi-Fi. Nothing is reachable from the internet.

**Requirements:** a Windows 10/11 PC and an Android phone with Chrome, on the same Wi-Fi.

## Setup

### 1. On the PC

1. Download this repo (**Code → Download ZIP** and unzip it, or `git clone`).
2. Double-click **`setup.cmd`** and answer the questions (Enter accepts the default).

It installs what's needed (Python, yt-dlp, mkcert and optionally ffmpeg), prepares phone access,
and opens XVid in your browser. Windows asks for permission twice: once to trust XVid's
certificate, and once to let your phone through the firewall.

### 2. On the phone

1. In XVid on the PC, click **Add a phone**.
2. On the phone, connect to the same Wi-Fi and scan the QR code with the camera.
3. Follow the page that opens:
   - **Trust your PC:** download the certificate and install it in Settings.
     This is the only manual part, about 6 taps. Android requires it to be done by hand.
   - **Open XVid:** logs the phone in automatically.
   - **Install app:** adds XVid to the home screen and the **Share** menu.
4. In the X app, open a post with a video: **Share → XVid**. If it's not listed, tap **More** and pin it.

Saved videos go to **Downloads**. Google Photos shows them under *Library → Download*.

## Day to day

| | |
|---|---|
| `setup.cmd` | Run again anytime to change answers, or if the PC's Wi-Fi address changed |
| `start.cmd` | Start or restart XVid in a visible window (useful to see errors) |
| `stop.cmd` | Stop XVid |

If you chose to start XVid with Windows, it runs in the background after you log in.
Its log is in `%LOCALAPPDATA%\XVid\xvid.log`.

**Reserve the PC's address in your router** (DHCP reservation; setup prints the address).
Installed phones point to that address. If it changes, XVid warns you: run `setup.cmd` again
and add the phone again.

## Sensitive, protected and subscriber-only posts

yt-dlp needs to be logged in as you. It can only download what **your account** can see,
so subscriber-only videos need an active subscription on that account.

- **Easiest:** log in to X in **Firefox**, then set the user environment variable
  `XVID_COOKIES_BROWSER` to `firefox` (Start → "Edit environment variables for your account")
  and restart XVid. Chrome and Edge cookies can't be read on Windows (app-bound encryption).
- **Or:** with a browser extension such as *Get cookies.txt LOCALLY*, export your cookies while on x.com,
  save the file as `cookies.txt` in XVid's folder, and restart XVid (`start.cmd`). That's it.

Cookies are your X session. Keep them private (`cookies.txt` is git-ignored).
Heavy automated use can get an account flagged.

## Configuration

User environment variables, read when XVid starts:

| Variable | Default | Purpose |
|---|---|---|
| `XVID_LIBRARY` | `%USERPROFILE%\Videos\XVid` | Where videos are saved |
| `XVID_TOKEN` | contents of `token.txt` | Password for logging in by hand (pairing makes it unnecessary) |
| `XVID_COOKIES_BROWSER` | none | e.g. `firefox`: use that browser's X login |
| `XVID_COOKIES_FILE` | `cookies.txt` in XVid's folder, if present | Path to a Netscape `cookies.txt` |

## How it works

```
Phone (installed XVid app) --home Wi-Fi--> PC :8443 HTTPS  the app (FastAPI) --> yt-dlp --> library folder
Phone before trusting the PC -----------> PC :8000 HTTP   only the setup page and the certificate
```

- **Why a certificate:** Android only puts web apps in the Share menu if they're served over
  HTTPS the phone trusts. `setup.cmd` uses [mkcert](https://github.com/FiloSottile/mkcert) to
  create a private certificate authority on the PC and a certificate for the PC's Wi-Fi address.
  The phone installs only the authority's *public* certificate. Its private key never leaves
  the PC (in `mkcert -CAROOT`); don't share that folder.
- **Pairing:** the QR code holds a one-time code (10 minutes). It's in the URL `#fragment`,
  so it's never sent over the plain-HTTP connection.
- **Security:** the PC itself is always trusted. Other devices need pairing (or the token),
  and repeated wrong attempts get blocked for 5 minutes.
- **Phone downloads:** a browser can't run yt-dlp, so the PC looks the video up and streams
  it straight through to the phone. Nothing is saved on the PC. If X only offers separate
  audio and video, the PC merges them in a temporary folder and deletes it within 30 minutes.
- **The library folder only holds videos.** Thumbnails, video info and temporary files live in
  its hidden `.xvid` subfolder.
- yt-dlp updates to its nightly build on every start, because X breaks it often.
- Python packages live in `%LOCALAPPDATA%\XVid\venv`, outside the repo (and OneDrive).

**Uninstall:** run `stop.cmd`. Delete the repo folder and `%LOCALAPPDATA%\XVid`, remove
`XVid` from the Startup folder (`shell:startup`), run `mkcert -uninstall`, and on the phone
remove the certificate (*Settings → Encryption & credentials → User credentials*).

## Troubleshooting

- **A download fails:** restart XVid (`start.cmd`) to get the latest yt-dlp. For sensitive or
  subscriber-only posts, see the cookies section above.
- **The phone says "Can't reach the PC":** the PC is off or XVid isn't running, the phone isn't
  on the same Wi-Fi, or the PC's address changed (run `setup.cmd` again).
- **The setup page never shows "Certificate installed":** check it's under *User credentials*
  in the phone's settings, then use *Continue anyway*. If Chrome still warns, reinstall the certificate.
- **XVid isn't in the Share menu:** it must be *installed* (the **Install app** button,
  or Chrome menu ⋮ → **Install app**).
- **Windows asked whether Python can use networks:** allow it on **Private** networks.
