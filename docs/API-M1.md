# API contract for M1 (verified on server 2.1.3, Oct 23 2026)

Extracted from `GET /openapi.json` (121 paths) and from real responses on the dev server.
Real fixtures (do not commit): `/tmp/opencode/ocm/{sessions.json,msgs.json,openapi2.json}`.

Auth: `Authorization: Basic base64(user:password)`; all responses are JSON with
the `{ "data": ... }` wrapper (except where noted). Timestamps in **epoch milliseconds**.

## GET /api/session

Query: `limit` (string), `order` = `asc|desc`, `search`, `parentID`, `archived`,
`directory`, `project`, `subpath`, `cursor`.

```json
{"data":[{
  "id":"ses_...", "parentID":null, "projectID":"9884...",
  "agent":"orchestrator",
  "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"},
  "cost":0.000861444,
  "tokens":{"input":5590,"output":12,"reasoning":0,"cache":{"read":5248,"write":0}},
  "outcome":"succeeded",                       // succeeded | failed | interrupted
  "time":{"created":1792791042489,"updated":1792791043724,"idle":...,"viewed":...,"archived":null},
  "title":"Greeting",
  "location":{"directory":"/Users/<user>"}
}],
 "cursor":{"previous":null,"next":"<token>"}}
```

Optional fields: `fork`, `subpath`, `metadata`, `permissions`, `revert`.
Real list: 5 top-level sessions, `parentID` null.

## GET /api/session/{sessionID}/message

Query: `limit`, `order` = `asc|desc`, `cursor`, `type`.
Response: `{"data":[...],"cursor":{"previous":"<tok>","next":"<tok>"}}`.
With `order=desc` the first element is the most recent; `cursor.previous` is used to
load older messages.

Messages are **polymorphic on `type`**:

```jsonc
// user
{"id":"msg_...","time":{"created":1792705642259},"type":"user",
 "text":"...", "files":[], "agents":[]}

// assistant: the text lives in the parts of content
{"id":"msg_...","time":{...},"type":"assistant",
 "agent":"orchestrator",
 "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"},
 "content":[ {"type":"reasoning","text":"...","native":...,"time":{...}},
             {"type":"text","text":"..."},
             {"type":"tool","id":"...","name":"bash","executed":true,"state":{...},"time":{...}} ],
 "finish":"stop",            // stop | tool-calls | ...
 "rawFinish":..., "cost":0.00114405, "tokens":{...}}

// session state machine
{"id":"msg_...","time":{...},"type":"idle","outcome":"..."}
```

Other possible `type` values (OpenAPI contract): `agent-switched`, `model-switched`,
`location-switched`, `synthetic`, `shell`, `skill`, `error`, various `status`.
Rule: **ignore gracefully** every unknown type/part (never crash the parser).

## GET /api/session/active

Real response with no active session: `{"data": {}}` (empty object, not `null`).
Treat empty `data` as "no active session".

## GET /api/session/{sessionID}/permission  (for M4)

`{"data":[{"id":"...","sessionID":"...","action":"...","resources":["..."],
"save":["..."],"metadata":{},"source":{"type":"tool","messageID":"...","id":"..."},
"message":"..."}]}`

## Rendering notes for M1

- Session title: `title`; if empty/absent → `id`.
- Secondary line: `agent` · `model.id` · relative age from `time.updated` · `cost` (USD, 4 decimals).
- Status badge: `outcome` (succeeded/failed/interrupted) or "active" if present in `/api/session/active`.
- Chat: only `text` parts render markdown-ish (M1: plain text, lines preserved);
  `reasoning` collapsed ("reasoning · N lines"); `tool` as a `tool: <name>` chip, expandable
  to show `state` (truncated JSON).
- `idle`/`status` → a thin separator, not a bubble.
