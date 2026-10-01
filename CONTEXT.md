# XVid

Download X (Twitter) videos on an Android phone or a Windows PC, and move them between the phone and your PCs.

## Language

### Devices

**Phone app**:
The XVid app on the Android phone. It downloads videos on its own, anywhere, with no PC needed.
_Avoid_: PWA, web app (when meaning the phone)

**PC**:
A Windows computer running XVid. It is only reachable from the phone on the same home network.
_Avoid_: server, host

**Home PC**:
The PC that other PCs join through an **Add a PC** code.

**Pairing**:
Linking the phone app to a PC by scanning that PC's **Add a phone** QR code. Afterwards the phone trusts and can log in to that PC and every PC that joined it.

**X login**:
The X account session a device uses to download sensitive or protected posts. The phone app and the PCs each have their own.
_Avoid_: cookies (when meaning the concept)

**PC id**:
What identifies a PC to the phone app and the other PCs. A PC's address can change; the phone app finds each PC on the home network by its id.
_Avoid_: address (when meaning which PC)

**Reachable**:
A PC is reachable when the phone is on the same network as that PC and XVid is running there.
_Avoid_: online, on

### Libraries

**Phone library**:
The videos the phone app has downloaded or saved, kept on the phone.

**PC library**:
The videos stored on one PC. It can be browsed from the phone only while that PC is reachable.
_Avoid_: library (unqualified, when the phone library could be meant)

### Moving videos

**Phone download**:
The phone app downloads an X video into the phone library itself.

**To PC**:
Send an X *link* to a PC, which downloads the video into its PC library. If the PC isn't reachable, the link waits in the queue.

**Upload**:
Send a video *file* from the phone (from the phone library or anywhere in the gallery) to a PC library. Only possible while that PC is reachable.
_Avoid_: To PC (that's for links)

**Save to phone**:
Copy a video from a PC library into the phone library.

**Queue**:
To PC links waiting on the phone for their PC to become reachable.
