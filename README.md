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
| `setup.cmd` | Run again anytime to change answers |
| `start.cmd` | Start or restart XVid in a visible window (useful to see errors) |
| `stop.cmd` | Stop XVid |

If you chose to start XVid with Windows, it runs in the background after you log in.
Its log is in `%LOCALAPPDATA%\XVid\xvid.log`.

The phone app finds each PC on the Wi-Fi by itself, so there's no need to reserve the PC's address
in your router: if the address changes, the app keeps working without running `setup.cmd` or pairing
again. (XVid used in the phone's *browser* still points to the old address: run `setup.cmd` again and
add the phone again.)

## More than one PC

One phone app can use several PCs, for example a desktop and a laptop. It works with whichever
ones are on: each PC that's on shows its own library, and downloads to the phone go through any of them.

1. Pick a **home PC**, ideally the one that's on most often, and set it up and pair the phone as above.
   The phone installs the app from it. After that, the app opens even when the home PC is off.
2. On the home PC, open XVid and click **Add a PC**. You get a one-time code (10 minutes).
3. On the other PC, get XVid (download or clone this repo, or let OneDrive sync it) and run
   **`setup.cmd`**. When it asks *"Is XVid already set up on another PC?"*, answer **yes** and paste the code.
4. Open the XVid app on the phone once while the home PC is on, so it learns about the new PC.

The other PC gets the home PC's certificate authority and login, so the phone trusts it and is
already logged in: no second certificate and no second app. With both PCs on, **To PC** and
**Upload** go to the PC chosen under the link box.

## Sensitive, protected and subscriber-only posts

yt-dlp needs to be logged in as you. It can only download what **your account** can see,
so subscriber-only videos need an active subscription on that account.

- **Easiest:** log in to X in **Firefox**, then set the user environment variable
  `XVID_COOKIES_BROWSER` to `firefox` (Start → "Edit environment variables for your account")
  and restart XVid. Chrome and Edge cookies can't be read on Windows (app-bound encryption).
- **Or:** with a browser extension such as *Get cookies.txt LOCALLY*, export your cookies while on x.com,
  save the file as `cookies.txt` in XVid's folder, and restart XVid (`start.cmd`). That's it.

Cookies are your X session. Keep them private (`cookies.txt` is git-ignored).
If XVid's folder is synced between your PCs (e.g. OneDrive), they all use the same `cookies.txt`.
Heavy automated use can get an account flagged.

## Configuration

User environment variables, read when XVid starts:

| Variable | Default | Purpose |
|---|---|---|
| `XVID_LIBRARY` | `%USERPROFILE%\Videos\XVid` | Where videos are saved |
| `XVID_TOKEN` | contents of `token.txt` | Password for logging in by hand (pairing makes it unnecessary) |
| `XVID_COOKIES_BROWSER` | none | e.g. `firefox`: use that browser's X login |
| `XVID_COOKIES_FILE` | `cookies.txt` in XVid's folder, if present | Path to a Netscape `cookies.txt` |
| `XVID_DATA` | `%LOCALAPPDATA%\XVid` | This PC's token, certificates, name and list of PCs |
| `XVID_HTTPS_PORT` / `XVID_HTTP_PORT` | `8443` / `8000` | Ports, if those are taken |
| `XVID_PHONE_VIEW` | off | `1` shows the phone's view in the PC's browser (for development) |
| `XVID_RELEASES_REPO` | the GitHub repo this folder was cloned from | `owner/name` of the GitHub repo whose Releases hold the phone app, for **Add a phone**. A ZIP download has no git remote: set this, or `"releases_repo"` in `xvid.json` |

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
- **Security:** the PC's own browser is trusted, but only on XVid's own pages, so other websites
  can't use it. Other devices need pairing (or the token), and repeated wrong attempts get blocked
  for 5 minutes. The phone app sends its login as a header; video and thumbnail links carry a
  separate key that only opens media.
- **Finding PCs:** each PC announces itself on the home network with mDNS / DNS-SD (service
  `_xvid._tcp`, UDP port 5353), with its id and name; `app/discovery.py` answers for it. The phone
  app looks for that service, matches what it finds to its paired PCs by id, and otherwise tries
  each PC's last known address. It accepts any certificate signed by the PCs' authority, whatever
  address it was made for, so a PC's certificate keeps working after its address changes.
- **Several PCs:** the app keeps a copy of itself on the phone (a service worker) and a list of
  your PCs, and asks each one whether it's on. Joining a PC copies the home PC's certificate
  authority and token over HTTPS. The code pins the home PC's certificate, so nothing is sent to an impostor.
- **Phone downloads:** a browser can't run yt-dlp, so the PC looks the video up and streams
  it straight through to the phone. Nothing is saved on the PC. If X only offers separate
  audio and video, the PC merges them in a temporary folder and deletes it within 30 minutes.
- **The library folder only holds videos.** Thumbnails, video info and temporary files live in
  its hidden `.xvid` subfolder.
- yt-dlp updates to its nightly build on every start, because X breaks it often.
- Python packages and this PC's own state live in `%LOCALAPPDATA%\XVid`, outside the repo
  (which may be synced between PCs).

**Uninstall:** run `stop.cmd`. Delete the repo folder and `%LOCALAPPDATA%\XVid`, remove
`XVid` from the Startup folder (`shell:startup`), run `mkcert -uninstall`, and on the phone
remove the certificate (*Settings → Encryption & credentials → User credentials*).

## Troubleshooting

- **A download fails:** restart XVid (`start.cmd`) to get the latest yt-dlp. For sensitive or
  subscriber-only posts, see the cookies section above.
- **The phone says "Can't reach the PC":** the PC is off or XVid isn't running, the phone isn't
  on the same Wi-Fi.
- **The setup page never shows "Certificate installed":** check it's under *User credentials*
  in the phone's settings, then use *Continue anyway*. If Chrome still warns, reinstall the certificate.
- **XVid isn't in the Share menu:** it must be *installed* (the **Install app** button,
  or Chrome menu ⋮ → **Install app**).
- **Windows asked whether Python can use networks:** allow it on **Private** networks.
- **A PC is missing on the phone:** open the app once while the home PC is on, so it learns about
  new PCs.
- **The app doesn't find a PC whose address changed:** the app looks for PCs on the Wi-Fi
  (UDP port 5353). Run `setup.cmd` once on that PC so the firewall lets it answer, and check the
  network is set to **Private** in Windows.

## Tests

`tests/` is a pytest suite for the PC's HTTP API: login and lockout, pairing, the PC list and
joining, To PC jobs, upload, thumbnails, delete, the PC library listing, and media links.
It runs the app in-process (FastAPI's test client) with a temporary library and data folder and
a fake yt-dlp, so it never touches the network, your library or your token. Tests only talk to
the app over HTTP.

```
python -m pip install -r requirements-dev.txt
python -m pytest
```

## The Android phone app

The native phone app is in `android/`: a plain Kotlin `core` module with all the behaviour
(tested on the JVM with fakes) and a thin Android `app` module around it. Building needs JDK 17
and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `android/local.properties`):

```
cd android
gradlew :core:test        # the core module's tests
gradlew assembleDebug     # one APK per CPU type in app/build/outputs/apk/debug
```

Most phones need the `arm64-v8a` APK. If the project is in a synced folder such as OneDrive and
the build fails with "Cannot snapshot … not a regular file", set `XVID_BUILD_DIR` to a folder
outside it and the build outputs go there instead.

Local builds are `-dev` versions and don't check for updates.

**Pairing the phone app:** in XVid on the PC, click **Add a phone**; in the app, tap **Pair with
a PC** and scan the code in step 2. That code holds the PC's address, a one-time code (10 minutes)
and the SHA-256 fingerprint of the PCs' certificate authority. The app fetches the authority's
public certificate from the PC, checks it against the fingerprint, and only then sends the code,
over a connection that trusts that authority alone. The app trusts it for its own connections
only, never system-wide, so there's no certificate to install in Android settings. Pairing with
one PC covers every PC that joined it: the app learns them from any reachable PC's PC list.
The app finds the PCs on the Wi-Fi by their ids (see *Finding PCs* above), so a PC's new address
needs no new pairing.

**X login on the phone:** the phone app has its own X login, separate from the PCs'
`cookies.txt`. In the app's Settings, tap **Log in to X** and log in with your username and
password (Google sign-in doesn't work inside the app). The session cookies stay in the app's
private storage and go to yt-dlp with every phone download. Nothing checks whether the login has
expired: a download that needs a login notifies "Log in to X in XVid", tapping it opens the login
page, and the download retries by itself once you've logged in.

### Releasing the phone app

Pushing a version tag runs `.github/workflows/release.yml`: it runs the core module's tests,
builds the signed release (one APK per CPU type) and publishes it as a GitHub Release. The app
checks GitHub Releases and shows a link when there's a newer version (it never downloads or
installs by itself), and the PC's **Add a phone** shows a QR code to the latest release.

The signing key never goes in the repo (it's public). Set it up once:

1. Make a key and keep the file and passwords somewhere safe. Every release must be signed
   with the same key, or phones can't update:
   ```
   keytool -genkeypair -keystore xvid-release.jks -alias xvid -keyalg RSA -keysize 4096 -validity 10000
   ```
2. In the GitHub repo, **Settings → Secrets and variables → Actions**, add:
   - `XVID_KEYSTORE_BASE64`: the key file in base64. In PowerShell:
     `[Convert]::ToBase64String([IO.File]::ReadAllBytes("xvid-release.jks")) | Set-Clipboard`
   - `XVID_KEYSTORE_PASSWORD` and `XVID_KEY_PASSWORD`: the passwords you chose
   - `XVID_KEY_ALIAS`: `xvid`

Then release with a tag:

```
git tag v1.0.0
git push origin v1.0.0
```
