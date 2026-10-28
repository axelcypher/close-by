# CloseBy architecture

## Components

| Component | Tech | Role |
| --- | --- | --- |
| `backend/` | Kotlin, Ktor, SQLite | Devices, persons, frames, pairing, call signaling, Immich proxy, FCM, APK delivery |
| `shared/` | Kotlin (JVM) | API models shared by backend and Android app |
| `android/` | Kotlin, Compose | One app, two modes: **frame** (the tablet) and **contact** (a relative's phone; admins also get the admin pages of the web client) |
| `web/` | TypeScript | Contact/admin client for the PC, served by the backend |
| `desktop/` | Tauri | Thin Windows shell around the hosted web client (tray, autostart) |
| LiveKit | SFU | Media for the calls; the backend only issues room tokens |

Deployment: `axelcypher/gitops-homelab`, `apps/docker/closeby`. Public hostname
`closeby.pendzialek.net`; `/rtc` goes to LiveKit, everything else to the backend.

## Model

- **Person**: someone the frame can call. Name and avatar are set on the
  person's own device during setup; an admin can override the name shown on
  the frame (`displayName`) and replace the picture
  (`PUT /api/admin/persons/{id}/avatar`). Admins are persons with `isAdmin`.
- **Device**: anything holding a token. `kind` is `frame`, `phone` or
  `desktop`. Phones and desktops belong to a person; frames don't. An admin
  can hand a device to another person (`PUT /api/admin/devices/{id}`); a
  person left without devices is merged into the target (frame contacts,
  admin rights, picture) and deleted.
- **Frame**: settings of a frame device (Immich album, slideshow timing, night
  mode, ...) and its ordered contact list (persons, each with `autoAnswer`).
  An admin can give the frame its own contact picture
  (`PUT /api/admin/frames/{id}/avatar`, read via `GET /api/frames/{id}/avatar`);
  its persons see it in their list of frames and when it calls.
- **Call**: one LiveKit room. A frame calls a person, or a person's device calls
  a frame. Calling a person rings all of their devices; the first to accept
  takes the call, the others stop ringing.

## Pairing

Every device gets its own random bearer token; the backend stores only its
SHA-256. Revoking a device deletes its access immediately.

| Flow | Started on | Confirmed by |
| --- | --- | --- |
| First admin | phone/PC: `POST /api/register` with `adminCode` (Vault `CLOSEBY_ADMIN_CODE`) | - |
| New contact | admin: `POST /api/admin/invites` -> link/QR | relative's phone: `POST /api/register` with the invite code |
| Frame | tablet: `POST /api/pairing/start` -> shows QR | admin scans: `POST /api/pairing/claim` |
| Extra device of a person (PC) | PC: `POST /api/pairing/start` -> shows QR | the person's phone scans: `POST /api/pairing/claim` |

The device that started a pairing polls `GET /api/pairing/{id}` with its poll
secret until it receives its token. A claim may name the `expectedKind` (the
admin pages send `frame`); a code of another kind is refused with 409 and
burnt, so nobody can pass off a PC's code as a frame's.

## Calls

```
caller                      backend                         callee device(s)
  POST /api/calls  -------->  create call + room
  <-- token, livekitUrl       ws "call.incoming" ---------->  frame / desktop
  join room                   FCM data "call.incoming" ---->  phones
                                                  <-------  POST /api/calls/{id}/accept
  <-- ws "call.accepted"      ws "call.taken" ------------>  the other devices
                                                  -------->  token, join room
  POST /api/calls/{id}/end    ws "call.ended" ------------>  everyone involved
```

Unanswered calls end as `missed` after `CALL_RING_TIMEOUT_SECONDS`.
`POST /api/calls/{id}/decline` ends the call for all callee devices. FCM
messages go out in order on a queue of their own, so a slow FCM never delays
the HTTP answer. Clients don't rely on every event arriving: they stop ringing
on their own after 60 s, drop a late duplicate of a ring they already handled,
treat the other side joining the room as "accepted", and end the call when the
other side doesn't show up in the room (30 s) or leaves it (15 s).

## Realtime channel

`GET /api/ws` (WebSocket). The first client message must be
`{"type":"auth","token":"..."}` (browsers can't set headers on WebSockets, and
tokens must not end up in access logs as query strings). An unknown token
closes the socket with 1008; a missing or garbled auth message (slow network)
with 4408, which clients treat like any other drop. The Android app signs out
only after `/api/me` refused its token twice, a minute apart. Server events:
`call.incoming`, `call.accepted`, `call.declined`, `call.ended`, `call.taken`,
`config.changed`.

Further events: `presence.changed` (a device came online or went offline,
broadcast to every connected device) and `frame.command` (admin command for a
frame: `maintenance` lifts the kiosk for ten minutes, `update` looks for a new
APK right away).

## Frames on a tablet

The frame works on any Android device, but a dedicated tablet should run
CloseBy as **device owner** (set once via adb on a device without accounts):

```bash
adb shell dpm set-device-owner net.pendzialek.closeby/.device.AdminReceiver
```

Then CloseBy is the home screen and runs in lock task mode (kiosk), camera,
microphone and notifications are granted without prompts, the keyguard and
status bar are off, the screen stays on while plugged in, and updates install
silently (checked every 6 hours and on `frame.command update`). A foreground
service keeps the websocket alive; an incoming call while the UI is hidden
shows a full-screen notification. If the power button turns the screen off
while the frame is charging (and it is not night), the app wakes it again. Without device owner the frame still works,
but asks for permissions and update confirmations.

A locked frame that has been offline for five minutes (new router, changed
Wi-Fi password) offers "WLAN einrichten" on its offline banner: the same
ten-minute maintenance window as the admin command, straight into the Wi-Fi
settings.

## Photos

The backend reads the frame's Immich album with its own API key, caches the
asset list for a few minutes and the preview images on disk. Frames only ever
talk to the backend: `GET /api/frame/slideshow`, `GET /api/photos/{assetId}`.

## App updates

CI builds the signed APK and bakes it into the backend image
(`/app/apk/closeby.apk` + `version.json`). The versionCode is
`run_number * 100 + run_attempt`; before the image is built CI checks that the
APK is signed with the certificate production publishes in
`/.well-known/assetlinks.json` and that its versionCode is above the published
one, and only the current tip of `main` is deployed (a re-run of an old run
must not roll back migrations or APKs). `GET /api/app/android` returns the
latest version, with a download URL per version; frames running as device owner
install it silently. No update replaces the app during a call. A frame that
restarts within three minutes of its last start (perhaps a crashing release)
looks for an update after 5 s instead of 60 s, so a fix can arrive without
anybody on site. On
Android 12+ a self-update needs no confirmation either once CloseBy may install
apps (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`): phones check every six hours on
Wi-Fi while the app is closed (`UpdateJob`), and the contact screen offers a
newer version with a button, which also leads through that permission. The APK
is also downloadable without login under `/download`.

The web client checks after every websocket reconnect (each deploy restarts
the backend) whether the page names a newer bundle, and reloads once no call
is going on and nobody looks at it (or right away from the banner). This is the
only update channel of the Windows app, which has no updater of its own.

Database migrations are append-only and tracked by `PRAGMA user_version`. An
older image never lowers that version, and tables or columns that exist
already are skipped, so a rollback followed by a roll-forward is safe.
