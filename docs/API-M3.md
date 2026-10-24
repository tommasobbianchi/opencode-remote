# API Contract for M3 — OMO drawer (agents, skills, MCP, models)

Verified against the dev server 2.1.4 on Oct 24 2026 with real calls (data in `/tmp/opencode/ocm/*.json`).

All endpoints under `/api/*` require Basic auth and respond with a `{data:...}` envelope
unless stated otherwise. Mutating POSTs respond **204 No Content**.

## Catalogs (read)

### GET /api/agent — 10 agents
```json
{"location":{"directory":"/Users/<user>"},"data":[{
  "id":"orchestrator","name":"orchestrator",
  "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"},
  "request":{"settings":{},"headers":{},"body":{}},
  "description":"AI coding orchestrator that delegates…",
  "mode":"primary|subagent|…","hidden":false,
  "permissions":[...],
  "system":"<4.7k characters of prompt>"
}]}
```
- **Ignore `system`** in the drawer (dead weight); use `id, name, description, mode, hidden, model`.
- Filter out `hidden == true` (compaction, title, summary) and do **not** show `mode == "subagent"` in the main list (or in a separate section).
- Per-agent model is optional (`model` absent on build/plan).

### GET /api/skill — 16 skills
`{"data":[{"id","name","description","path","content"}]}` — `content` can be large: **do not
request it for the list** (the endpoint returns it anyway; discard it while parsing).

### GET /api/mcp — 3 servers
`{"data":[{"name":"context7","status":{"status":"connected"}}]}` — observed status: `connected`.
Other possible statuses (per the contract): `disabled`, `failed`, `needs_auth`… treat as string.

### GET /api/location
`{"directory":"/Users/<user>","project":{"id":"…","directory":"…","canonical":"…"}}`

### GET /api/model — 90 models (⚠️ security)
Each entry has `id, modelID, providerID, family, name, capabilities, settings, …` and **`settings.apiKey`
may contain a real provider key**. Rule: parse **only** `id, modelID, providerID,
name, family, capabilities.input/output`; **discard `settings`** and never log it. Do not persist.

## Actions

| Action | Call | Body | Result |
|---|---|---|---|
| Switch agent | `POST /api/session/{id}/agent` | `{"agent":"fixer"}` | 204 |
| Switch model | `POST /api/session/{id}/model` | `{"model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"}}` | 204 |
| Activate skill | `POST /api/experimental/session/{id}/skill` | `{"id":"opencode","resume":null}` | 204 |
| Slash command | `POST /api/session/{id}/command` | `{"name":"review","text":"…"}` (`text` required) | 204 |
| Interrupt | `POST /api/session/{id}/interrupt` | — | 200 |
| Background session | `POST /api/session/{id}/background` | — | 204 (semantics not documented: **do not use in M3**) |

`Agent.ID` and `Skill.ID` are strings; `Model.Ref` requires `id` + `providerID`, `variant` optional.

## Child sessions (background tasks)

`GET /api/session?parentID={sessionID}` → same shape as `/api/session`.
Observed: `{"data":[],"cursor":{...}}` when there are no children.
The `parentID` field does **not** appear in the session object returned by the top-level list: to
find out whether a session has children, query by `parentID` (one call per open session is
sufficient) or watch the `session.created` events with `data.parentID`.

## Slash commands

`GET /api/command` → 9 entries `{name, description}` (init, review, deepwork, reflect, loop, interview, …).
No state fields: they are static.

## Notes for the UI

- The drawer has 4 tabs: **Sessions · Agents · Skills · MCP**, with the location/project at the top and
  the task badge (children running).
- Caching: agents/skills/MCP/models rarely change → 5-minute in-memory TTL; manual refresh from the drawer.
- `switchAgent`/`switchModel` affect the **open session** (not global).
- After `switchAgent`/`switchModel` the UI must reflect the value returned by the server: re-run
  `GET /api/session/{id}` instead of assuming local success.

## Live verification of actions (Oct 24 2026)

Run on a throwaway session (then deleted: `DELETE` → 204):

| Step | Result |
|---|---|
| `POST /api/session/{id}/agent` `{"agent":"fixer"}` | **204**, then `GET /api/session/{id}` shows `agent:"fixer"` |
| `POST /api/session/{id}/model` `{"model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"}}` | **204**, then `GET` shows `model.id/providerID/variant` |
| `GET /api/session?parentID={id}&limit=5` | `{"data":[],"cursor":{...}}` |

**Caution:** a session created but never used **has no `agent` and `model` fields** in the
response of `GET /api/session/{id}`; they only appear after an explicit switch (or after the first turn).
The UI must therefore show "default" instead of an empty row, and the model picker must
work even starting from that state.
