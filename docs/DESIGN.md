# Android client for a remote OpenCode — project document

Status: design draft, no code written yet.
Date: Oct 22 2026.
Target: dev server (macOS, OpenCode 2.1.3).

---

## 1. Goal

Control from Android the OpenCode sessions running on the dev server, attaching to
**the already running server** (not an independent instance), so as to see and
continue the same sessions used from the TUI.

Requirement stated by the user: a side panel (swipe from the right edge toward
the left) showing a list in OMO sidebar style — sessions, agents, skills, MCP,
task states.

### Non-goals (first version)

- Remote terminal (for that, Moshi already exists).
- File editor, worktree management, PTY, advanced settings.
- Replica of the animated companion or the TUI animations.
- Direct invocation of individual tools: you go through a prompt, a slash command, or a skill.
- Running without Tailscale active.

---

## 2. Verified technical constraints

Source: read-only survey of Oct 22 2026 on the dev server.

| Constraint | Detail |
|---|---|
| Server version | OpenCode 2.1.3, API V2 (`/api/*`) |
| Transport | server on `127.0.0.1:49374`; exposed since Oct 22 2026 via `tailscale serve` on `https://<machine>.<tailnet>.ts.net` (tailnet only, valid TLS) |
| Authentication | HTTP Basic, user `opencode`, password/token as the password |
| Pairing | `POST /api/pair` → one-time code; `GET /auth/connect/{code}` with `Accept: application/json` → token to use as password |
| Message send | `POST /api/session/{id}/prompt` is **async**: it answers with an ack (`Session.Inbox.User`), not with the output |
| Streaming | `GET /api/event` → SSE. **Live-only, no replay.** Periodic heartbeat |
| History recovery | `GET /api/session/{id}/message`; for precision `GET /api/experimental/session/{id}/log?seq=<n>&follow=true` |
| Approvals | `GET /api/session/{id}/permission`, `POST .../permission/{requestID}/reply` with `once` / `always` / `reject` |
| Agents | `GET /api/agent`, `POST /api/session/{id}/agent` |
| Models | `GET /api/model`, `POST /api/session/{id}/model` |
| Skill / commands | `GET /api/skill`, `GET /api/command`, `POST /api/session/{id}/command` |
| MCP | `GET /api/mcp` (connection state) |
| Health / version | `GET /api/info` → version, pid, urls, capabilities |
| Managed port | **fixed at 49374** by default (`opencode service set port` to change it). One managed instance per port: a second isolated instance conflicts, none starts |
| TUI-only | Companion, sidebar, tab, palette, statusline, animations |

Note: `1.18.35` is the version of the `@opencode-ai/plugin` package, **not** of the runtime.
A client built on the V1 contract would not work here.

---

## 3. Decisions to close before writing code

### D1 — Server lifecycle — ✅ RESOLVED (Oct 22 2026)

**Outcome: the service survives the exit of the client that started it.**

Verifications:

1. The service runs in **its own session and process group** (`PGID = PID`, `STAT Ss`,
   `TTY ??`): it was started with `setsid`, so a SIGHUP to the window does not
   reach it.
2. Test in an isolated sandbox (temporary XDG dirs, port 49375): an ephemeral client
   started the service and then exited. The service moved to **PPID 1**, kept
   its own session and group, and answered `HTTP 200` on `/api/info` after
   10 seconds.
3. In the context the TUI receives from the server, `service` exposes only `reconnect` and
   `restart`: **there is no `stop`** invokable by the client on exit.

Consequences:

- No `opencode service start` needed: the service is born with the first TUI and stays.
- The Android client can connect even with the TUI closed, provided the machine is
   on and the service was not explicitly stopped.
- A status banner remains in the app: if the service was stopped or the machine
   is asleep, the connection fails and a clear message must be shown.
- Not tested directly: closing the real TUI (it would have terminated the working
   session). The structural evidence and the sandbox test cover the same path.

### D2 — Transport and authentication — ✅ ACTIVE (Oct 22 2026)

Configuration in place:

```
tailscale serve --bg 49374
→ https://<machine>.<tailnet>.ts.net  (tailnet only)
    proxy → http://127.0.0.1:49374
```

Verifications run:

| Test | Outcome |
|---|---|
| `GET https://…/api/info` without credentials | **401** — the auth is not bypassed by the proxy |
| Same endpoint with Basic `opencode:<password>` | **200**, `version 2.1.3`, `pid 37490` |
| TLS certificate | valid (`ssl_verify_result = 0`), IP `<ip-tailnet>` (tailnet) |
| `tailscale funnel status` | no public exposure: "tailnet only" |

Rules to maintain:

- **Never** `tailscale funnel`, **never** `opencode pair --remote` (they expose to the internet).
- Pairing token instead of the permanent service password.
- The server keeps listening only on loopback: the only "open" port is the
  Tailscale one, and only inside the tailnet.
- Disable: `tailscale serve --https=443 off`.

### D3 — MVP scope

Drawer + chat + approvals + agent/model switch. The rest after.

---

## 4. App architecture

```
UI (Compose)
  └─ ViewModel (StateFlow)
        └─ Repository
             ├─ OpenCodeApi      (REST, OkHttp + kotlinx.serialization)
             ├─ EventStream      (OkHttp SSE, foreground service)
             └─ LocalCache       (Room)
```

- **Kotlin**, **Jetpack Compose** + Material 3, minSdk 29, target 36.
- **OkHttp** + `okhttp-sse`; **kotlinx.serialization** with models generated
  from the OpenAPI (`/openapi.json`, 146 operations, 293 schemas) — models and
  interfaces only, the HTTP client stays hand-written.
- **Room** for the cache of sessions and messages (instant open, offline read).
- **DataStore + Android Keystore** for the token.
- **Foreground service** (`dataSync`) that holds the SSE and shows the notifications.

Clean separation: `EventStream` produces normalized events in a `Flow`; the
ViewModels know neither SSE nor JSON.

---

## 5. Data model

Local entities (Room):

```
ServerConfig   id, label, baseUrl, username="opencode", tokenRef, lastSeenAt, version
SessionRow     id, projectId, title, agent, modelProvider, modelId, costUsd,
                tokensIn/Out/Reasoning, state (running|idle), createdAt, updatedAt, lastSeq
MessageRow     id, sessionId, role, createdAt, partsJson, seq
AgentRow       id, name, mode, provider, model, variant        (cache, TTL)
SkillRow       id, name, description, path                      (cache, TTL)
McpRow         name, status                                    (cache, TTL)
ApprovalRow    id, sessionId, action, resourcesJson, message, status
```

UI state (not persisted):

```
DrawerState    isOpen, tab (Sessions|Agents|Skill|MCP)
ChatState      messages, streamingText, isBusy, pendingApprovals, error
Connection     Disconnected | Connecting | Connected | AuthFailed | VersionMismatch
```

The drawer reads from `SessionRow` + `AgentRow` + `SkillRow` + `McpRow`: no
blocking call on open, refresh in the background.

---

## 6. Screens

1. **ServerSetup** — name, URL (`https://<machine>.<tailnet>.ts.net`), connection
   state, "Pair" button.
2. **Pairing** — QR scanner of the `/auth/connect/{code}` link generated by the TUI
   (`opencode pair` / `/pair` command), or paste-link. On success it stores the
   token in the Keystore and does `GET /api/info` to validate the version.
3. **SessionList** — session list with state, cost, agent; pull-to-refresh;
   "new session" (agent + model).
4. **Chat** — message timeline, streaming, input with attachments, interrupt button,
   approvals badge.
5. **Right side drawer** — see §7.
6. **ApprovalsSheet** — permission request with Once / Always / Reject.
7. **Settings** — server, token (revoke), theme, diagnostics (version, ping, SSE state).

Navigation: `Scaffold` + `ModalNavigationDrawer` with the drawer **on the end side**
(right), opened via edge-swipe from right to left and via icon. The native system
gesture must be respected: use `gesturesEnabled` and a lateral threshold to
not conflict with the back gesture.

---

## 7. The "OMO sidebar" drawer

Content, with the data source of each block:

| Block | Data | Endpoint |
|---|---|---|
| Sessions | title, agent, state, cost, tokens | `GET /api/session`, `GET /api/session/active` |
| Agents | name, mode, model, variant | `GET /api/agent` |
| Skill | name, description | `GET /api/skill` |
| MCP | name, connection state | `GET /api/mcp` |
| Project/location | current directory | `GET /api/location` |
| Task/subagents | synthetic state | SSE events + `Session.Info` |

Interactions: tap on a session → opens the chat; tap on an agent → `session.switchAgent`;
tap on a skill → activates it (`POST /api/experimental/session/{id}/skill`) or inserts it
into the prompt; tap on MCP → read-only detail.

Update: agents rarely change → cache with a 5 minute TTL; sessions
update on events and on pull-to-refresh.

---

## 8. Connection flow

### 8.1 First startup (pairing)

1. On the machine: `opencode pair --url https://<machine>.<tailnet>.ts.net`
   (prints a one-time link + QR).
2. On the app: scan the QR → `GET {link}` with `Accept: application/json`.
3. The server answers with the token → stored in the Keystore.
4. `GET /api/info` → version compared with the minimum supported (2.x).
5. `GET /api/session` → cache populated.

If the token expires or is revoked: 401 → `AuthFailed` state → redo pairing.

### 8.2 Normal startup

```
GET /api/info                       → version, pid, capabilities
GET /api/session?limit=50&order=desc  → list
GET /api/session/active             → eventual in-progress session
GET /api/event (SSE)                → live
```

### 8.3 Sending a prompt

```
POST /api/session/{id}/prompt
{ "text": "...", "delivery": "steer" | "queue", "files": [...] }
→ 200 { data: Session.Inbox.User }   // ack only
```

The output arrives exclusively via events. UI: optimistic user message,
"queued" indicator until the first event of the session arrives.

### 8.4 Reconnection and recovery (critical point)

SSE has no replay: every disconnection is a potential loss of events.

Algorithm:

1. On `onFailure`/`onClosed`: `Connecting` state, exponential backoff
   (1s → 2s → 4s → … max 30s) with jitter.
2. On reconnection: `GET /api/event` again **and** full re-read of
   `GET /api/session/{id}/message` for the open session.
3. ~~Fill the gap with
   `GET /api/experimental/session/{id}/log?seq={lastSeq}&follow=true`.~~ **Corrected on
   Oct 23 2026:** that endpoint does not replay the history — it answers immediately with
   `log.synced` and the current seq, then stays listening. Use it only as an
   alignment ping; the recovery is always the message re-read (point 2). See
   `docs/API-M2.md` §4.
4. Deduplication by `messageID` and by `event.id`.
5. Heartbeat timeout: if nothing arrives for 30s, consider the connection dead and
   reconnect. The network loss is detected immediately by the `NetworkCallback` of
   `ConnectivityManager` (attempts suspended until the network returns).

Golden rule: **the UI must never depend on the order of the events**; the messages
are the truth, the events are an accelerator.

### 8.5 Background

Foreground service with a persistent notification and active SSE. Android can suspend the
network in Doze: on waking apply §8.4. Without FCM there is no delivery guarantee of an
immediate notification to a killed process — accepted and documented limit.

---

## 9. Approvals

- Source: `GET /api/session/{id}/permission` + events.
- Local notification with three actions: Once / Always / Reject.
- Reply: `POST /api/session/{id}/permission/{requestID}/reply`.
- `Always` has a permanent effect on the server: show it with explicit confirmation,
  never as the default.
- If the request is expired (session interrupted), the reply returns an error:
  handle it with a "request no longer valid" message and a refresh.

---

## 10. Error handling

| Situation | UI state | Action |
|---|---|---|
| 401 | `AuthFailed` | propose a new pairing |
| Server version ≠ 2.x | `VersionMismatch` | block, explain |
| Tailscale absent | `Disconnected` | banner "private network not active" |
| Server unreachable | `Disconnected` | retry with backoff |
| Session deleted | back to the list | toast |
| SSE closed by the server | `Connecting` | §8.4 |

---

## 11. Security

- `cleartextTrafficPermitted=false`; only HTTPS (Tailscale serve).
- Token in the Keystore, never in the logs, never in Room.
- No logging of headers, prompt, or file content.
- Revocation: token deletion + new pairing.
- Exposed surface: only the tailnet; no funnel, no public port.
- The QR contains a one-time token: it should be shown only on the machine, not shared.

---

## 12. Milestones

| M | Content | Acceptance criterion |
|---|---|---|
| M0 | Spike: Tailscale serve + pairing + `GET /api/info` + SSE | From the phone I see the version and receive at least one event |
| M1 | Session list + read-only chat + history | I open a session and read the conversation |
| M2 | Prompt send + streaming + reconnection | The text arrives in real time and survives a network change |
| M3 | OMO drawer + agent/model switch + interrupt | The swipe opens the panel and the data are correct |
| M4 | Approvals with notifications | I approve from the notification without opening the app |
| M5 | Hardening | Lost network, expired token, server-off tests |

---

## 13. Risks

1. ~~**Service lifecycle** (D1)~~ — resolved: the service survives
   the client exit. The "service stopped or machine asleep" case remains, to
   be covered with a status banner.
2. **SSE without replay**: gaps in the messages if the reconnection is poorly handled.
   Mitigation: §8.4 and deduplication.
3. **`experimental` endpoint**: it may change. Isolate it behind an adapter with
   a fallback.
4. **Doze / battery**: notifications not guaranteed to a killed process.
5. **Contract drift**: OpenCode 2.x evolves; pin a minimum version and
   validate it with `/api/info` at startup.

---

## 14. Open questions

- Are the tailnet ACLs already restrictive or should they be set?
- Should the drawer also show the background tasks with detail, or only a badge?
- Is a read-only offline mode needed, or is the cache for startup enough?
