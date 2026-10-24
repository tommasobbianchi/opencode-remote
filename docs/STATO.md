# Project status (session close, Oct 24 2026)

Generic Android client to control an **OpenCode 2.x** server from the phone
(no personal preset: empty URL, user's credentials).
Package `io.github.mirkotappini.opencoderemote`.
Last delivered build: **0.9.0-m7**, `versionCode 19`, sha256 `4d71b1e7…` (app name **OC Remote**, neon OC icon).

## Where

| What | Where |
|---|---|
| Source | `serber:/home/mirko/opencode-mobile` (**not a git repo**) |
| Build | `build-host:/Users/<user>/opencode-mobile` (JDK+SDK in `~/android-toolchain`) |
| Distribution | `serber:python3 serve.py` → `http://<ip-tailnet>:8091/app.apk` (always the latest from `dist/current.txt`) |
| Docs | `docs/DESIGN.md`, `docs/API-M1..M4.md`, `docs/UI-M1..M4.md`, `docs/THEME.md`, `docs/M5-CHECKLIST.md`, `docs/M2-NOTE.md` |

## Status by milestone

- **M0** transport (Tailscale serve, Basic auth, SSE) — done
- **M1** session list + read-only chat + history — done
- **M2** prompt send, live streaming, interrupt, reconnection with resync — done
- **M3** OMO drawer (3 tabs: Agents/Skill/MCP), model picker with search, session row, task badge, informational subagents, working-agents indicator — done
- **M4** approvals: in-chat panel, badge, notifications with actions, foreground service, encrypted credentials (Keystore) + auto-connect — done
- **M5** hardening: version gate, error taxonomy, 401 policy, vanished session, NetworkCallback, 30 s SSE watchdog, correct countdown, launcher icon — done
- **M7** identity: OC Remote name, new icon (adaptive without wordmark, legacy with wordmark, monochrome) — done
- **M6** live turn view: reasoning with duration, tool card with command/output/state/exit,
  text in streaming, consecutive steps in a single bubble — done (spec in `docs/UI-M6-LIVE.md`)

Tests: **78 JVM**, all green, run with `./gradlew --no-daemon --no-build-cache clean testDebugUnitTest assembleDebug` on the dev server.

## Change server from the list (0.6.4-m5)

There was no way to get back to the login screen from the session list (only
the OMO drawer → "Disconnect", too hidden). Now in the header the old host/version
eyebrow is a **tappable pill** (`ServerChip`) with a status dot and a
chevron: it opens a "Server" `ModalBottomSheet` with host, URL, version, and the
`Cancel` / `Change server` buttons. `Change server` = `AppViewModel.disconnect()`: it stops
the stream, the service, and the notifications, deletes the encrypted credentials, and returns
to `SetupScreen` **pre-filled** with the last URL/user/password (no new
state machine). The drawer stays unchanged, with the same copy about the consequences.

## Technical findings not to rediscover

- `/api/experimental/session/{id}/log?seq=N` **does not** replay the history: it answers `log.synced` with the current seq.
- Real approval events: `permission.asked` (carries the whole request) and `permission.replied`.
- The approval reply uses the **`decision`** field (`once|always|reject`), not `reply`.
- `GET /api/model` exposes `settings.apiKey`: the client must discard `settings`.
- In the model catalog `modelID` is the family (duplicated), `id` is the concrete reference: list keys and `Model.Ref` use `id`.
- An `opencode service stop` on the dev server **does not hold** the service: it restarts by itself in ~8 s.
- SSE heartbeat every ~10–12 s; client watchdog 30 s.

## Turn events contract (M6)

Captured from server 2.1.4 and put in fixtures (`app/src/test/resources/fixtures/sse_tool.jsonl`,
`sse_reasoning.jsonl`):

- `session.reasoning.started {assistantMessageID, ordinal, native}` / `.delta {delta}` / `.ended {text}`
- `session.tool.input.started {id, name}` / `.delta` / `.ended {text = JSON of the input}`
- `session.tool.called {id, input, executed}` / `.progress {metadata}`
- `session.tool.success {content[], metadata{status,truncated,exit}, executed}` / `.failed`
- `session.text.started {ordinal}` / `.delta {delta}` / `.ended {text}`

Gotcha: **`executed` does not mean "permission to execute"** — on 170 real tool parts
it is `false` even with `state.status = completed`. The real outcome is `state.status`
(`completed` / `error`); `Denied` only with explicit messages ("Permission denied", …).
The REST parts have `time.{created,ran,completed}`: the duration is computed from there.

## Open threads

1. ~~Restore the generated password~~ → **user's decision: do not touch it.**
   The service test password stays the one set by hand: no
   `opencode service unset password`, no restart to regenerate it (Oct 24 2026).
2. ~~**git init + first commit**~~ → done: private repo `github.com/mirkotappini/opencode-remote`.
3. ~~**Colors of the OMO panel**~~ → **resolved Oct 24** (see below)
4. Eventual **icon recoloring** from electric blue to petrol `#8ED8C6`.
5. OFL font license to be included as a note in the repo.

## OMO panel colors fix (local patch, Oct 24)

The `oh-my-opencode-slim` plugin (up to 3.0.5) maps the v2 theme with old
keys: `background.default`, `text.default`, `text.subdued`, `border.default`.
The 2.1.4 host exposes `currentTokens()` with `background.base`, `text.base`,
`text.muted`, `border.base` (and `text.feedback.*.base`), so the panel background, text, and
border fell back to the opentui defaults.

- Idempotent script: `build-host:~/.config/opencode/omo-theme-fix.py` (`-v` for the detail).
- The patcher rewrites `v2ThemeView()` in **all** the copies loaded by the plugin:
  `~/.cache/opencode/packages/oh-my-opencode-slim*/…` **and**
  `~/.cache/opencode/npm/<pkg>@latest/<hash>/…` (the latter is the one actually
  in use: revealed by `OMO_THEME_DEBUG=1`, which writes the resolved view to
  `/tmp/omo-theme-debug.json`).
- The tokens are color objects (`toInts() -> [r,g,b,a]`), not strings: the patch
  converts them to `#rrggbb` before passing them to the renderer.
- Resolved view verified: `#9d7cd8` accent, `#0a0a0a` bg, `#141414` raised,
  `#484848` border, `#eeeeee` text, `#808080` muted, `#1e1e1e` hover,
  `#7fd88f` success, `#f5a742` warning.
- **To re-run after every plugin update** (the dependency is `@latest`, so
  an update reintroduces the bug): in the dev server's `~/.zshrc` there is the `opencode()`
  function that runs the script before the binary
  (`command /opt/homebrew/bin/opencode`), backup `.zshrc.bak-omo-theme`.
  Do not use `command -v opencode` inside the function: in zsh it returns the function name
  and recurses.

## Suggested next step

Remaining: eventual icon recoloring (petrol `#8ED8C6`), OFL license note,
delta publish throttling (§4.4 of `docs/UI-M6-LIVE.md`) if stutters are noticed.
Pick up again: "let's resume opencode mobile".
