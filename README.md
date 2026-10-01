# XVid

Download X (Twitter) videos on your Android phone, anywhere, and move them between the phone and
your Windows PCs at home.

- In the X app: **Share → XVid → Download to phone**. The phone app downloads the video itself, on
  any network, with no PC needed. It lands in `Movies/XVid`, where your gallery and Google Photos show it.
- **To PC** sends a link to a PC instead, which downloads it into its PC library. Away from home,
  the link waits on the phone and goes to the PC by itself once you're back on the home Wi-Fi.
- On the home Wi-Fi, browse and stream each PC's library from the phone, **Save to phone** any of
  it, and **Upload** videos from the phone to a PC.
- On the PC, paste links in XVid in the browser to download them into its PC library.

The PCs are only reachable on your home Wi-Fi. Nothing is reachable from the internet.

**Requirements:** a Windows 10/11 PC, and an Android 8 (or newer) phone.

## Setup

### 1. On the PC

1. Download this repo (**Code → Download ZIP** and unzip it, or `git clone`).
2. Double-click **`setup.cmd`** and answer the questions (Enter accepts the default).

It installs what's needed (Python, yt-dlp, mkcert and optionally ffmpeg), prepares phone access,
and opens XVid in your browser. Windows asks for permission twice: once to trust XVid's
certificate, and once to let your phone through the firewall.

### 2. On the phone

In XVid on the PC, click **Add a phone**:

1. **Install the XVid app:** scan the first QR code with the phone's camera. It downloads the app
   from this repo's GitHub Releases; open the file to install it. (Android asks once to allow
   installing apps from your browser.)
2. **Pair:** connect the phone to the same Wi-Fi as the PC, open the XVid app, tap **Pair with a
   PC** and scan the second QR code. There's no certificate to install in Android settings.

Then, in the X app, open a post with a video: **Share → XVid**. If it's not listed, tap **More** and pin it.

**Used the old XVid web app on this phone before?** It's gone: the PC no longer serves it. Remove it
from the home screen (hold its icon → **Uninstall** or **Remove**), optionally remove the old
certificate (*Settings → Encryption & credentials → User credentials*), then install the app and
pair as above. Videos you saved before stay in your Downloads.

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
again.

## More than one PC

One phone app can use several PCs, for example a desktop and a laptop. It works with whichever
ones are on: each PC that's on shows its own PC library on the phone.

1. Pick a **home PC**, ideally the one that's on most often, and set it up and pair the phone as above.
2. On the home PC, open XVid and click **Add a PC**. You get a one-time code (10 minutes).
3. On the other PC, get XVid (download or clone this repo, or let OneDrive sync it) and run
   **`setup.cmd`**. When it asks *"Is XVid already set up on another PC?"*, answer **yes** and paste the code.
4. Open the XVid app on the phone once while the home PC is on, so it learns about the new PC.

The other PC gets the home PC's certificate authority and login, so the phone trusts it and is
already logged in: no second pairing. **To PC** and **Upload** let you choose the PC.

## Sensitive, protected and subscriber-only posts

yt-dlp needs to be logged in as you. It can only download what **your account** can see,
so subscriber-only videos need an active subscription on that account. The phone app and the PCs
each have their own X login.

**On the phone:** in the app's Settings, tap **Log in to X** (see *X login on the phone* below).

**On the PCs:**

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
| `XVID_TOKEN` | contents of `token.txt` | Password for logging in by hand from a browser on another device (pairing makes it unnecessary for the phone) |
| `XVID_COOKIES_BROWSER` | none | e.g. `firefox`: use that browser's X login |
| `XVID_COOKIES_FILE` | `cookies.txt` in XVid's folder, if present | Path to a Netscape `cookies.txt` |
| `XVID_DATA` | `%LOCALAPPDATA%\XVid` | This PC's token, certificates, name and list of PCs |
| `XVID_HTTPS_PORT` | `8443` | Port, if it's taken |
| `XVID_HTTP_PORT` | `8000` | Port for this PC's browser only, before `setup.cmd` set up phone access |
| `XVID_RELEASES_REPO` | the GitHub repo this folder was cloned from | `owner/name` of the GitHub repo whose Releases hold the phone app, for **Add a phone**. A ZIP download has no git remote: set this, or `"releases_repo"` in `xvid.json` |

## How it works

```
Phone (XVid app) --anywhere------> X, with yt-dlp in the app ------> Movies/XVid on the phone
Phone (XVid app) --home Wi-Fi----> PC :8443 HTTPS (FastAPI) --> yt-dlp --> PC library folder
PC's browser     --this PC-------> PC :8443 HTTPS, the web UI (static/)
```

- **Why a certificate:** the phone app talks to the PCs over HTTPS. `setup.cmd` uses
  [mkcert](https://github.com/FiloSottile/mkcert) to create a private certificate authority on the
  PC and a certificate for the PC signed by it. The app trusts that authority for its own
  connections only (see *Pairing the phone app* below). Its private key never leaves the PC (in
  `mkcert -CAROOT`), except to PCs that join it; don't share that folder.
- **Security:** the PC's own browser is trusted, but only on XVid's own pages, so other websites
  can't use it. Other devices need pairing (or the token), and repeated wrong attempts get blocked
  for 5 minutes. The phone app sends its login as a header.
- **Finding PCs:** each PC announces itself on the home network with mDNS / DNS-SD (service
  `_xvid._tcp`, UDP port 5353), with its id and name; `app/discovery.py` answers for it. The phone
  app looks for that service, matches what it finds to its paired PCs by id, and otherwise tries
  each PC's last known address. It accepts any certificate signed by the PCs' authority, whatever
  address it was made for, so a PC's certificate keeps working after its address changes.
- **Several PCs:** the app keeps a list of your PCs and asks each one whether it's on. Joining a PC
  copies the home PC's certificate authority and token over HTTPS. The code pins the home PC's
  certificate, so nothing is sent to an impostor.
- **The library folder only holds videos.** Thumbnails, video info and temporary files live in
  its hidden `.xvid` subfolder.
- yt-dlp updates to its nightly build on every start of XVid on the PC, because X breaks it often.
  The phone app updates its own yt-dlp when a download fails, and checks weekly on Wi-Fi.
- Python packages and this PC's own state live in `%LOCALAPPDATA%\XVid`, outside the repo
  (which may be synced between PCs).

**Uninstall:** run `stop.cmd`. Delete the repo folder and `%LOCALAPPDATA%\XVid`, remove
`XVid` from the Startup folder (`shell:startup`), run `mkcert -uninstall`, and on the phone
uninstall the XVid app.

## Troubleshooting

- **A download fails:** on the phone, the app updates yt-dlp and retries by itself; posts that need
  an X login ask for one. On a PC, restart XVid (`start.cmd`) to get the latest yt-dlp. For
  sensitive or subscriber-only posts, see the X login section above.
- **A PC shows as not reachable on the phone:** the PC is off or XVid isn't running, or the phone
  isn't on the same Wi-Fi. That's normal away from home; To PC links wait in the queue meanwhile.
- **Windows asked whether Python can use networks:** allow it on **Private** networks.
- **A PC is missing on the phone:** open the app once while the home PC is on, so it learns about
  new PCs.
- **The app doesn't find a PC whose address changed:** the app looks for PCs on the Wi-Fi
  (UDP port 5353). Run `setup.cmd` once on that PC so the firewall lets it answer, and check the
  network is set to **Private** in Windows.
- **A PC says it doesn't accept this phone anymore:** its token changed (for example it was set up
  again without joining). Tap **Pair with a PC** in the app and scan its **Add a phone** code again.

## Tests

`tests/` is a pytest suite for the PC's HTTP API: login and lockout, pairing, the PC list and
joining, To PC jobs, upload, thumbnails, delete, the PC library listing, media links, finding PCs
on the network (the mDNS answers), and that the old phone web app's endpoints are gone.
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

**PC libraries on the phone:** each reachable PC's section on the main screen shows its newest
videos, and **See all** opens its whole PC library. Tapping a PC's name (or the Phone library's
title) folds that library away; the app remembers it. The phone downloads are behind the
**Downloads** button at the top, next to **Settings**. On a whole library (**See all**), pinch to
show more and smaller videos a row (up to 4), or spread your fingers for fewer and bigger (down to 1).
Pulling a screen down from its top reloads it (the main screen checks the PCs again).
Tapping a video streams it from the PC inside
the app (other video players wouldn't trust the PCs' certificate authority). Under it, **Save to
phone** copies it into `Movies/XVid` with a progress notification, and **Delete** removes it from
that PC library after asking first. Videos the PC has no thumbnail for (uploads, files dropped
into its folder) get one from a frame the app reads over the network, and the PC keeps it too.

**Upload from the phone:** hold a video in the phone library and choose **Upload to a PC**, or tap
**Upload a video** on the phone library screen to pick any video from the gallery (or **Upload** in
a PC's section to send it straight to that PC). Only reachable PCs can be chosen: with none
reachable, the app says the phone needs to be on the same Wi-Fi as a PC running XVid. A
notification shows the progress, and the video appears in that PC library with a thumbnail the
phone makes.

**To PC and the queue:** Share → XVid opens a small sheet: **Download to phone** (the default) or
**To PC**, with the PC you chose last already picked. The main screen's **To PC** button does the
same for a pasted link. A reachable PC gets the link right away and downloads it into its PC library.
If the PC isn't reachable, the link waits in the **queue** on the phone, even across restarts, and
never expires. It's sent in the background when the phone joins a Wi-Fi network (and when the app
opens), without opening the app. The queue screen lets you remove a link or send it to another PC.

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
