# API contract for M4 — approvals

Verified against dev server 2.1.4 on Oct 24 2026 with real calls.

## Pending requests

`GET /api/session/{sessionID}/permission` → `{"data":[Permission.Request]}`
(session with no requests: `{"data":[]}`)

```json
{"id":"per_…","sessionID":"ses_…","action":"bash","resources":["rm -rf /tmp/x"],
 "save":["bash"],"metadata":{},"source":{"type":"tool","messageID":"msg_…","id":"tool_…"},
 "message":"…"}
```

- `id` has pattern `^per`.
- `action` is the operation (e.g. `bash`, `edit`, `webfetch`), `resources` the resources involved.
- `save` lists what would be remembered in the "always" case.
- `source.type` observed: `tool` (with `messageID` and the tool `id`).

## Reply

`POST /api/session/{sessionID}/permission/{requestID}/reply`

```json
{"decision":"once"}          // once | always | reject
{"decision":"reject","message":"optional reason"}
```

- **The field is named `decision`**, not `reply`. Values: `once`, `always`, `reject` (`Permission.Reply`).
- Reply is **204 No Content**. Nonexistent session → 404 `SessionNotFoundError`.
- Expired/nonexistent request → **404** with body:
  `{"_tag":"PermissionNotFoundError","requestID":"per_fake","message":"Permission request not found: per_fake"}`
  The UI must translate this to "request no longer valid" and redo `GET …/permission`.

## What it is NOT

`POST /api/session/{sessionID}/permission` (without `requestID`) **does not create a request**: it
registers a rule/answer and returns `{"data":{"id":"per_…","effect":"allow"}}`.
Do not use it in M4.

## SSE events — VERIFIED

Captured live on Oct 24 2026 with a genuine request (prompt reading a `*.env`, rule
`ask`), on a throwaway session later deleted:

```json
{"id":"evt_…","created":1792796421246,"type":"permission.asked",
 "location":{"directory":"/Users/<user>"},
 "data":{"id":"per_…","sessionID":"ses_…","action":"read",
         "resources":["perm-probe.env"],"save":["*"],
         "source":{"type":"tool","messageID":"msg_…","id":"call_…"}}}
```

- **`permission.asked`** carries the whole `Permission.Request` in `data` (same shape as the GET).
- **`permission.replied`** arrives after the reply and is the resolution confirmation (dedupe/cancel the notification).
- Extract `data.id` (requestID) and `data.sessionID`; no nested wrapper.

So the event is the **primary source**; the `GET /api/session/{id}/permission` stands as
alignment on open, on reconnection, and in the foreground (useful if the app had been closed).
Periodic polling is no longer needed: if you keep it, use it only as a fallback after a
reconnection.

## Notifications (app side)

- Dedicated channel (high importance) + `POST_NOTIFICATIONS` runtime on Android 13+ (targetSdk 36).
- One notification per request, dedupe by `requestID`; direct `Once` / `Reject` actions, `Always`
  **only after explicit confirmation** (DESIGN §9: has a permanent effect on the server, never the default).
- On resolution (from the app or the notification) the notification must be cancelled.
- The foreground service holding the SSE must declare `foregroundServiceType="dataSync"` and the
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions; without FCM there is no delivery
  guarantee to a killed process (accepted limit, DESIGN §8.5).

## Service token

The foreground service must authenticate without the Activity: the token is stored encrypted
(AES/GCM with the key in the Android Keystore, private preferences) on the first successful `connect()` and
cleared by "Disconnect". Never in cleartext, never in the logs.
