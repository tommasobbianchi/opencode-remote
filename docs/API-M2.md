# API Contract for M2 — prompt, streaming, reconnection

Live-verified on Oct 23 2026 against the dev server (OpenCode **2.1.4**), using a throwaway
session then deleted (`DELETE /api/session/{id}` → 204). No user data in this document.
Raw fixtures: `/tmp/opencode/ocm/sse_run.jsonl` (15 events from one minimal turn).

## 1. Sending a prompt

`POST /api/session/{sessionID}/prompt`, `Content-Type: application/json`

```json
{"text":"...","delivery":"steer","files":[],"agents":[],"skills":[]}
```

- `text` is required. `delivery`: `steer` (de-facto default) | `queue`.
- Response **200**: immediate ack, not the output:
  `{"data":{"id":"msg_...","sessionID":"ses_...","time":{"created":...},"type":"user","payload":{"text":"..."},"delivery":"steer"}}`
- Errors: 400, 401, 404, 409 (ConflictError), 500.

The output arrives **only** via SSE. The UI must show the user message optimistically
and a "queued" state until `session.execution.started` arrives.

## 2. SSE event stream

`GET /api/event`, `text/event-stream`, **global** (all sessions: filter by
`data.sessionID`), **live-only without replay**.
Frames: `data: {json}` + periodic `: heartbeat` (ignore lines starting with `:`).

Real envelope:

```json
{"id":"evt_...","created":1792792392493,"type":"session.text.delta",
 "location":{"directory":"/Users/<user>"},
 "data":{"sessionID":"ses_...","assistantMessageID":"msg_...","ordinal":0,"delta":"pong"},
 "durable":{"aggregateID":"ses_...","seq":6,"version":1}}
```

`durable.seq` is monotonic **per session**; `durable.aggregateID` is the sessionID.

### Real sequence of a minimal turn (arrival order)

| # | type | useful data |
|---|---|---|
| 1 | `server.connected` | `{}` — connection established |
| 2 | `session.created` | `sessionID, slug, version, projectID, location, title` |
| 3 | `project.updated` | `id, canonical, time` |
| 4 | `session.inbox.enqueued` | `inboxID, sessionID, item:{type:"user",payload:{text},delivery}` |
| 5 | `session.execution.started` | `sessionID` → **session busy** |
| 6 | `session.instructions.updated` | `delta` (hash) — ignore |
| 7 | `session.inbox.delivered` | `inboxID` — the prompt has been picked up |
| 8 | `session.step.started` | `agent, model, assistantMessageID, started` |
| 9 | `session.text.started` | `assistantMessageID, ordinal` |
| 10..n | `session.text.delta` | `assistantMessageID, ordinal, delta` → **append the text** |
| n+1 | `session.text.ended` | `assistantMessageID, ordinal, text` (final text of the part) |
| n+2 | `session.step.streamed` | `assistantMessageID` — checkpoint, ignorable |
| n+3 | `session.step.ended` | `assistantMessageID, finish, rawFinish, cost, tokens` |
| n+4 | `session.usage.updated` | `cost, tokens` — update the session cost |
| n+5 | `session.execution.succeeded` | `sessionID` → **session free** |

Expected but not captured (same shape, `data.sessionID` present): `session.execution.failed`,
`session.execution.interrupted`, `session.reasoning.*`, `session.tool.*`, `session.permission.*`,
`session.compacted`, `session.removed`, `session.title.updated`.
Rule: **unknown type → log and ignore**, never crash.

Note: `ordinal` identifies the `text` part inside the assistant message; more `ordinal` = more parts
(text interleaved with tools). M2 rendering can concatenate by ascending `ordinal`.

## 3. Interruption

`POST /api/session/{sessionID}/interrupt` (optional query `resume=true|false`) → 200
`SessionInterruptResponse`. After the interrupt, `session.execution.interrupted` arrives.

## 4. Reconnection — correction to DESIGN

Point §8.4.3 of DESIGN ("fill the gap with
`GET /api/experimental/session/{id}/log?seq={lastSeq}&follow=true`") **does not work as written**.
Verified: with `seq=0` and with `seq=5` the endpoint responds **immediately** with
`data: {"type":"log.synced","aggregateID":"<sid>","seq":10}` — i.e. it only announces the current seq and,
with `follow=true`, waits for new events. **It does not replay history.**

So the correct strategy:

1. On `onFailure`/`onClosed`: state `Connecting`, exponential backoff 1→2→4…max 30s with jitter.
2. On reconnection: **full re-read** of `GET /api/session/{id}/message?order=asc`
   (or `desc` + reverse) for the open session; the messages are the source of truth.
3. `GET /api/experimental/session/{id}/log?seq={lastSeq}&follow=true` can only be used as an
   **alignment ping** (read `log.synced.seq` to know if something was missed), not to
   recover events.
4. Deduplicate by `messageID`; during streaming, a delta applies only if
   `assistantMessageID` is not already closed by `session.text.ended` / `session.step.ended`.
5. Heartbeat: no data for 90s ⇒ dead connection ⇒ reconnect.

## 5. Session state

- `session.execution.started` → running
- `session.execution.succeeded|failed|interrupted` → idle (with outcome)
- `GET /api/session/active` → `{"data":{}}` if no active session
- `GET /api/session/{id}` → `Session.Info` with updated `outcome`, `cost`, `tokens`
