# Project status (Oct 24 2026)

Generic Android client to control an **OpenCode 2.x** server from the phone
(no personal preset: empty URL, user's credentials).
Package `io.github.mirkotappini.opencoderemote`, app name **OC Remote**.
Last delivered build: **0.13.2**, `versionCode 41`, sha256 `1e69c884…`.

## Where

| What | Where |
|---|---|
| Source | `serber:/home/mirko/opencode-mobile` (git repo, `main`) |
| Remote | `github.com/mirkotappini/opencode-remote` (private) |
| Build | `patchouli:~/opencode-mobile` (JDK+SDK in `~/android-toolchain`), sources synced with `rsync` |
| Distribution | `python3 serve.py` on serber → `http://<tailnet-ip>:8091/app.apk` (always the latest from `dist/current.txt`) |
| Docs | `docs/DESIGN.md`, `docs/API-M1..M4.md`, `docs/UI-M1..M4.md`, `docs/UI-M6-LIVE.md`, `docs/THEME.md`, `docs/UI-M8-ATTACHMENTS.md`, `docs/UI-M9-QUESTIONS-AND-COMMANDS.md` |

## Status by milestone

- **M0** transport (Tailscale serve, Basic auth, SSE) — done
- **M1** session list + read-only chat + history — done
- **M2** prompt send, live streaming, interrupt, reconnection with resync — done
- **M3** OMO drawer (Agents/Skill/MCP), model picker with search, session row, task badge, working-agents indicator — done
- **M4** approvals: in-chat panel, badge, notifications with actions, foreground service, encrypted credentials (Keystore) + auto-connect — done
- **M5** hardening: version gate, error taxonomy, 401 policy, vanished session, NetworkCallback, 30 s SSE watchdog, correct countdown — done
- **M6** live turn view: reasoning with duration, tool card with command/output/state/exit, streaming text, consecutive steps in a single bubble — done
- **M7** identity: OC Remote name, icon from the user's render (adaptive = full mark, legacy = plate, monochrome) — done
- **M8** attachments: one file per prompt (gallery, camera, generic files), client-side compression, inline `files` in the prompt, previews in the bubble — done
- **M9** server questions (form) as a panel above the composer + slash-command palette — done

Tests: **132 JVM**, all green, run with
`./gradlew --no-daemon --no-build-cache clean testDebugUnitTest assembleDebug` on the build host.

## Things not to rediscover

- `/api/experimental/session/{id}/log?seq=N` does **not** replay history: it answers `log.synced` with the current seq.
- Approval events: `permission.asked` (carries the whole request) and `permission.replied`; the reply uses **`decision`** (`once|always|reject`), not `reply`.
- `GET /api/model` exposes `settings.apiKey`: the client must discard `settings`.
- In the model catalog `modelID` is the family (duplicated), `id` is the concrete reference.
- An `opencode service stop` on the dev server does **not** keep it down: it comes back in ~8 s.
- SSE heartbeat every ~10-12 s; client watchdog 30 s.
- `executed` on tool events does **not** mean "permission granted": on 170 real tool parts it is `false` even with `state.status = completed`. The real outcome is `state.status`; `Denied` only on explicit messages.
- REST parts carry `time.{created,ran,completed}` (not `start`/`end`): durations come from there.
- **Attachments are inline only**: `POST …/prompt` accepts `files: [{uri,name?,description?}]` with `uri` = `data:<mime>;base64,…` (or `file://` for files already on the server). Measured limit: 4 MB decoded works, 6 MB returns 500 (`Schema validation failed … Prompt.Base64`). Accepted mimes: `image/*`, `application/pdf`, `application/json`, `text/*`, `application/octet-stream`.
- The stored user message exposes `files[].data` (inline) + `mime` + `source.type`; `source.type = "uri"` carries `uri` instead of `data`.
- **The `question` tool sends `string` *with* `options`**, not `multiselect`: it is a single choice. Parsing must keep `options` for string fields and the UI must render them as chips.
- Forms are created only by the agent (there is no `POST /api/form`): to test one, ask the agent to use the question tool.
- Slash commands are **not** expanded by the prompt endpoint: `/name args` must go through `POST /api/session/{id}/command` (`{name, text?, files?}`), otherwise the model receives the literal text.
- `WindowInsets.safeDrawing` includes the IME: the chat viewport shrinks when the keyboard opens.

## Turn events contract (M6)

Captured from server 2.1.4, fixtures in `app/src/test/resources/fixtures/`
(`sse_tool.jsonl`, `sse_reasoning.jsonl`):

- `session.reasoning.started {assistantMessageID, ordinal, native}` / `.delta` / `.ended {text}`
- `session.tool.input.started {id, name}` / `.delta` / `.ended {text = JSON of the input}`
- `session.tool.called {id, input, executed}` / `.progress {metadata}`
- `session.tool.success {content[], metadata{status,truncated,exit}, executed}` / `.failed`
- `session.text.started {ordinal}` / `.delta {delta}` / `.ended {text}`
- `session.step.started/.streamed/.ended {finish, cost, tokens}` / `.failed`

## Live performance and scrolling

- **Publish throttled**: `net/PublishGate.kt` (100 ms window, `now` injected) +
  `ChatReducer.publishedImmediately()` — only pure deltas are throttled; phase
  changes and turn ends publish at once. The ViewModel publishes a `ChatSnapshot`.
- **Auto-scroll is sticky**: `followBottom` is only disarmed by a real user
  drag (`DragInteraction.Start`) and re-armed when the list settles at the
  bottom or on send. Content signature (`partsScrollSignal`) counts text and
  reasoning, **not** tool output growth.
- `scrollToBottom()` needs a measured `scrollBy`: `scrollToItem(last)` aligns
  the item's *top*, and a turn bubble can be taller than the viewport.
- Foreground: on `ON_RESUME` the list realigns to the bottom (if following) —
  scrolls issued while the window was hidden are not applied, so after standby
  the chat used to stay at the stale position.
- Keyboard: on every IME inset step the list realigns without delay (lagging
  it to the end of the animation was visible as a step).

## Attachments (M8)

One file per prompt, max 50 MB picked, images downscaled to 1600 px / JPEG 80
(second pass 1280/70), non-image files only under 4 MB inline. Paperclip lives
**inside** the text pill, right-aligned. Preview row above the field, thumbnails
in the user bubble with a full-screen viewer, document chip otherwise.

## Questions and slash commands (M9)

- Form events `form.created` / `form.replied` / `form.cancelled` feed
  `UiState.forms` (plus `GET /api/form` on connect, session open and SSE reopen).
  The **panel above the composer** renders text, single choice, yes/no, numbers
  with min/max and multiselect; required fields gate "answer"; "cancel" sends
  `DELETE`. With a pending question the composer is disabled and "Stop" stays.
- Send button: enabled only with at least one letter or digit in the draft
  (`hasSendableText`), or with an attachment. Spaces, punctuation and symbols
  alone (`/`, `…`) do not enable it, and the ViewModel enforces the same rule.
- Enter on an empty composer is ignored (`acceptDraftChange`): it used to insert
  a newline and grow the pill for nothing.
- Command palette: typing `/` opens a scrollable, filterable sheet fed by
  `GET /api/command` (TTL 5 min); picking one writes `/name ` in the field and
  sending goes through `POST …/command`. "Cancel" suppresses the palette until
  the draft changes.

## Subagent flash (0.13.0)

When the server creates or updates a **child session** (`session.created` /
`session.updated` with `parentID`), the open chat shows a small chip in the
top-right corner for 3.8 s, with a 400 ms fade-out: agent name in mono plus the child session title.
`ServerEvent.ChildAgentStarted` carries the mother session as `sessionId` (the
payload's `sessionID` is the child), so the existing per-session filter works.
Duplicate updates for the same child are ignored (bounded set), and the tint is
a stable FNV-1a hash of the agent name over five theme tokens, so `designer`
and `fixer` look different on every device. Verified on the Fold: `fixer`
(amber) and `designer` (purple) chips with their child titles.

## Tool card without the command (0.13.0)

The card shows status, tool name, duration, `exit N`, output and "show all ·
N lines"; the command string is **not shown anywhere** (the detail sheet opened
from the card carries only the output). `part.command` stays in the model for
future use.

## OMO drawer gesture (0.12.4)

The panel used to open only from a 22 dp strip at the right edge, which is also
where the system back gesture wins: it often missed on the first try. Now
`Modifier.drawerOpenSwipe` on the root surface accepts a leftward swipe
**anywhere**: it waits for the touch slop, abandons on vertical/diagonal
movement (chat scrolling untouched), ignores long-press-then-drag (text
selection), multi-touch and rightward drags, and opens at 48 dp of horizontal
travel with 1.4x axis dominance. Disabled while a server question is pending.
Verified on the Fold: mid-screen swipe opens, 42 dp does not, vertical scroll
does not.

## Icon (0.10.2)

Generated from the user's render (`tools/icon/icon-source.png`) with
`tools/icon/make_icon.py` (needs Pillow: `uv run --with pillow`). Adaptive =
full mark without the plate at 66 %, alpha from luminance; legacy = whole plate;
background colour from the dark quantile of the frame (`#030202`). The
monochrome variant is the **mark silhouette**, not a font "OC".

## Open threads

1. ~~Restore the generated password~~ → **user's decision: do not touch it.** No
   `opencode service unset password`, no restart to regenerate it.
2. ~~git init + first commit~~ → done, private repo on GitHub.
3. ~~OMO panel colours~~ → resolved Oct 24 (local patch).
4. ~~Icon recolouring to petrol `#8ED8C6`~~ → dropped, not wanted.
5. ~~OFL font license note~~ → done: `docs/FONT-LICENSES.md`, linked from the README.
6. Optional: private GitHub release with the signed APK (only debug builds today).

## OMO panel colours fix (local patch, Oct 24)

`oh-my-opencode-slim` (up to 3.0.5) maps the v2 theme with old keys
(`background.default`, `text.default`, …) while host 2.1.4 exposes
`currentTokens()` with `background.base`, `text.base`, `text.muted`, `border.base`.
Idempotent script `build-host:~/.config/opencode/omo-theme-fix.py` rewrites
`v2ThemeView()` in every loaded copy of the plugin — the one actually used is
`~/.cache/opencode/npm/oh-my-opencode-slim@latest/<hash>/…` (found with
`OMO_THEME_DEBUG=1`). Tokens are colour objects (`toInts()` → `#rrggbb`).
Relaunched by the `opencode()` function in `~/.zshrc` (never use
`command -v opencode` inside it: zsh recursion).

## Suggested next step

Only the OFL license note is left, plus an optional signed release. Known gaps
in verification: real device flows for attachments (picker/camera) and the
slash palette were never exercised end to end (adb cannot give focus to the
compose text field on this device).
