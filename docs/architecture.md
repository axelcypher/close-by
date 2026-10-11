# CloseBy architecture

## Components

| Component | Tech | Role |
| --- | --- | --- |
| `backend/` | Kotlin, Ktor, SQLite | Devices, persons, frames, pairing, call signaling, Immich proxy, FCM, APK delivery |
| `shared/` | Kotlin (JVM) | API models shared by backend and Android app |
| `android/` | Kotlin, Compose | One app, two modes: **frame** (the tablet) and **contact** (a relative's phone) |
| `web/` | TypeScript | Contact/admin client for the PC, served by the backend |
| `desktop/` | Tauri | Thin Windows shell around the hosted web client (tray, autostart) |
| LiveKit | SFU | Media for the calls; the backend only issues room tokens |

Deployment: `axelcypher/gitops-homelab`, `apps/docker/closeby`. Public hostname
`closeby.pendzialek.net`; `/rtc` goes to LiveKit, everything else to the backend.

## Model

- **Person**: someone the frame can call. Name and avatar are set on the
  person's own device during setup; an admin can override the name shown on
  the frame (`displayName`). Admins are persons with `isAdmin`.
- **Device**: anything holding a token. `kind` is `frame`, `phone` or
  `desktop`. Phones and desktops belong to a person; frames don't.
- **Frame**: settings of a frame device (Immich album, slideshow timing, night
  mode, ...) and its ordered contact list (persons, each with `autoAnswer`).
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
secret until it receives its token.

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
`POST /api/calls/{id}/decline` ends the call for all callee devices.

## Realtime channel

`GET /api/ws` (WebSocket). The first client message must be
`{"type":"auth","token":"..."}` (browsers can't set headers on WebSockets, and
tokens must not end up in access logs as query strings). Server events:
`call.incoming`, `call.accepted`, `call.declined`, `call.ended`, `call.taken`,
`config.changed`.

## Photos

The backend reads the frame's Immich album with its own API key, caches the
asset list for a few minutes and the preview images on disk. Frames only ever
talk to the backend: `GET /api/frame/slideshow`, `GET /api/photos/{assetId}`.

## App updates

CI builds the signed APK and bakes it into the backend image
(`/app/apk/closeby.apk` + `version.json`). `GET /api/app/android` returns the
latest version; frames running as device owner install it silently. The APK
is also downloadable without login under `/download`.
