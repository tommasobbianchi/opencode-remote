# M9 — Server questions (form) and slash commands

Two gaps seen in use, same area of the code. Contracts verified against the
patchouli server 2.1.4 on Oct 24 2026.

## 1. Server questions ("form")

### 1.1 Why

When the agent needs an answer it stays **waiting**: the turn does not finish,
the session is `running`, so the composer is blocked and the only action is
the stop button ("Stop"). The server has a dedicated mechanism (form) that the app currently
ignores.

### 1.2 Verified contract

Endpoints (`openapi.json` of the server):

| method | path | body / response |
|---|---|---|
| GET | `/api/form` | `{location, data: Form.Info[]}` — pending forms (for resync) |
| GET | `/api/session/{sessionID}/form/{formID}` | `Form.Detail` |
| POST | `/api/session/{sessionID}/form/{formID}/reply` | `{answer: { <key>: value }}` |
| DELETE | `/api/session/{sessionID}/form/{formID}` | cancel |

SSE events (names present in the binary): `form.created`, `form.replied`,
`form.cancelled`. The `form.created` payload is the `Form.Detail` (to be
confirmed at runtime: the parser must degrade without crashing if fields are
missing).

`Form.Detail`:

```json
{ "id": "frm_…", "sessionID": "ses_…", "title": "…",
  "metadata": {…}, "state": {"status": "pending"},
  "fields": [ { "key": "choice", "type": "string", "title": "What do we do?",
                "description": "…", "required": true, "hidden": false,
                "options": [{"value": "a", "label": "A", "description": "…"}] } ] }
```

Field types (`Form.Field`): `string`, `boolean`, `integer`, `number`,
`multiselect` (with `options`), `external` (with `url`: not composable from the
app). Useful properties: `key`, `title`, `description`, `required`, `hidden`,
`when` (conditions: **ignored in v1**), `minimum`/`maximum` for numeric fields,
`options[]` for multiselect.

`Form.Value` (value of an answer): `string` | `number` | `boolean` |
`string[]`.

### 1.3 Behaviour in the app

- State: `UiState.forms: List<FormUi>` (the pending questions of the open
  session; in practice one) + `formsLoading`/`formsError` and the send outcome.
- Sources: `form.created` events (add), `form.replied` / `form.cancelled`
  (remove), plus `GET /api/form` on connect, on session open and on SSE
  reconnect (as for M4 approvals).
- UI: **panel pinned above the composer** (not in the list, so it is not lost
  while scrolling), visible only with an open session and a pending form:
  - eyebrow "question", title, description if present;
  - one control per field: text (one or more lines for a long `description`),
    checkbox for `boolean`, numeric field with numeric keyboard for
    `integer`/`number`, multi-selectable chips for `multiselect`,
    `external` → non-composable link with the note "fill in on the server";
  - fields with `hidden = true` are not drawn;
  - buttons: "reply" (disabled until the `required` fields are filled in) and
    "cancel" (DELETE);
  - errors: missing required field → short message; 404 → "question expired",
    the panel disappears and a resync is performed.
- Does the composer stay blocked while a question is pending? **No**: the
  server accepts prompts while waiting anyway, but to avoid confusion the
  composer stays writable only when there is no pending form; with a pending
  form the user sees the panel and the stop button ("Stop") stays available.
- TalkBack: the panel announces "opencode question" once only.

## 2. Slash commands

### 2.1 Why

Today typing `/review` sends the **literal** text to the model: the command is
not expanded (the TUI expands it client-side, the API has a dedicated endpoint).

### 2.2 Verified contract

| method | path | body |
|---|---|---|
| GET | `/api/command` | `{location, data: [{name, description}]}` |
| POST | `/api/session/{sessionID}/command` | `{name, text?, files?, agents?, skills?, delivery?}` |

Current list on the server (9): `init`, `review`, `deepwork`, `reflect`, `loop`,
`interview`, `implement`, `close`, `resume` — the app must read them at runtime,
not keep a fixed list.

### 2.3 Behaviour in the app

- Catalogue: `CommandUi(name, description)` loaded with `GET /api/command`
  (TTL like the other catalogues, e.g. 5 min), reloadable by hand.
- **Palette**: opens when the draft starts with `/` and does not contain spaces
  yet; **scrollable** `ModalBottomSheet` (`LazyColumn`) with one row per command
  (name in mono, `mutedText` description, 56 dp), filter on the text after `/`,
  tap → the draft becomes `/<name> ` and the cursor goes to the end; "cancel"
  closes it.
  The placeholder of the empty field becomes "Write… or / for commands".
- **Send**: if the draft is `/<name>` or `/<name> <arguments>` and `<name>` is in
  the catalogue → `POST /api/session/{id}/command` with `name` and `text` = the
  rest (possibly empty) and any attachments; otherwise → normal prompt.
  Unknown command → normal send (no blocking, it is text like any other).
- After sending a command: same optimistic bubble as a prompt (the typed text),
  then the resync brings the truth from the server.

## 3. Where things live

| Area | File |
|---|---|
| Form DTOs/events + commands | `net/Events.kt`, `net/Models.kt`, `net/OpenCodeApi.kt` |
| State and actions | `AppViewModel.kt` (`forms`, `replyForm`, `cancelForm`, `refreshForms`, `commands`, `loadCommands`) |
| Question panel + palette | `ChatScreen.kt` + `Forms.kt` (next to `Attachments.kt`) |
| Wiring | `MainScreen.kt` |
| JVM tests | parsing `form.created`, building the `answer` (mixed types), command/text alignment choice, parsing `GET /api/command` |

## 4. Acceptance criteria

1. The agent asks a question → the panel appears with the fields; I answer → the
   turn resumes and the panel disappears.
2. Form with an empty required field → "reply" disabled.
3. Numeric and multiselect form → the values arrive in the right types (`number`,
   `string[]`).
4. "Cancel" on the panel → DELETE and the panel disappears.
5. I reopen the session with a pending question → the panel is there (resync).
6. I type `/` → scrollable palette with all the server commands; filter by
   typing; tap → `/name ` in the field.
7. Sending `/review main` → the command is sent (not a literal prompt).
8. Green build with the existing tests + the new ones.
