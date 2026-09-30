# Native Android phone app instead of the installed web app

The phone app must download X videos anywhere, on any network, with no PC on. A browser can't run yt-dlp, so the installed web app always needed a reachable PC to download for it. We're replacing it with a native Android app (Kotlin + youtubedl-android) that runs yt-dlp and ffmpeg on the phone. PCs remain home-network-only: the phone reaches them only when on the same network, for browsing PC libraries, Upload and To PC.

## Considered Options

- **Keep the web app and add an internet-facing server running yt-dlp**: rejected. It breaks the "nothing reachable from the internet" rule, costs money, and puts the X login on a third-party machine.
- **Keep the web app and use Seal for downloads away from home**: rejected. XVid would stop being one app.

## Consequences

- The Android app becomes the largest part of the project and needs the Android SDK to build.
- The app trusts the PCs' private CA itself (its fingerprint comes in the pairing QR code), so the manual certificate install in Android settings, the phone setup page and the HTTP :8000 listener go away.
- PCs no longer download on the phone's behalf (`/api/direct`). Phone downloads always run on the phone, even at home.
- The phone keeps its own X login, independent of the PCs' `cookies.txt`.
- The PC browser UI stays, minus the phone-only parts (service worker, share target, phone layout).
